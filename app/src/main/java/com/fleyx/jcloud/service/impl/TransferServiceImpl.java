package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.TransferTaskStatus;
import com.fleyx.jcloud.common.event.TransferSubmittedEvent;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.TransferTaskMapper;
import com.fleyx.jcloud.model.bo.TransferItem;
import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.dto.OperationItemDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.TransferTask;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.TransferTaskVo;
import com.fleyx.jcloud.service.TransferService;
import com.fleyx.jcloud.service.support.FileMoveCopySupport;
import com.fleyx.jcloud.service.support.FileNodeSupport;
import com.fleyx.jcloud.service.support.RemoteMountSupport;
import com.fleyx.jcloud.service.support.UserSpaceSupport;
import com.fleyx.jcloud.util.FileConflictHelper;
import com.fleyx.jcloud.util.FileConflictResolver;
import com.fleyx.jcloud.util.FileNodeUtil;
import com.fleyx.jcloud.util.IdUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 跨来源传输服务实现。
 */
@Service
@RequiredArgsConstructor
public class TransferServiceImpl implements TransferService {

    private static final String OP_COPY = "copy";
    private static final String OP_MOVE = "move";
    private static final long WAIT_POLL_MILLIS = 300L;

    private final TransferTaskMapper transferTaskMapper;
    private final FileMapper fileMapper;
    private final FileNodeSupport fileNodeSupport;
    private final FileMoveCopySupport fileMoveCopySupport;
    private final RemoteMountSupport remoteMountSupport;
    private final UserSpaceSupport userSpaceSupport;
    private final FileConflictResolver conflictResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TransferTaskVo createTransfer(FileExecuteOperationDto dto, String opType, String userId) {
        if (!OP_COPY.equals(opType) && !OP_MOVE.equals(opType)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不支持的操作类型: " + opType);
        }
        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "传输项不能为空");
        }
        String targetParentId = FileNodeUtil.normalizeParentId(dto.getTargetParentId());
        FileNode targetParent = fileMoveCopySupport.resolveTargetParentNode(targetParentId, userId);
        List<FileNode> sources = dto.getItems().stream()
                .map(item -> fileNodeSupport.getOwnedNode(item.getId(), userId))
                .toList();
        fileMoveCopySupport.validateSourcesHomogeneous(sources);
        for (FileNode source : sources) {
            if (remoteMountSupport.isMountPoint(source)) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能复制/移动远程挂载点");
            }
        }
        if (OP_MOVE.equals(opType)) {
            fileMoveCopySupport.validateTargetNotSelfOrDescendant(sources, targetParent, targetParentId);
        }

        FileNode first = sources.get(0);
        String sourceType = first.getSourceType() == null ? FileNodeConstants.SOURCE_LOCAL : first.getSourceType();
        String targetType = targetParent == null || targetParent.getSourceType() == null
                ? FileNodeConstants.SOURCE_LOCAL : targetParent.getSourceType();
        String sourceMountId = first.getRemoteMountId();
        String targetMountId = targetParent == null ? null : targetParent.getRemoteMountId();
        if (sourceType.equals(targetType) && Objects.equals(sourceMountId, targetMountId)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "同来源操作请使用移动/复制接口");
        }

        List<TransferItem> items = buildItems(dto, sources, targetParentId, userId);
        if (items.isEmpty()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "所有文件均已跳过，无需传输");
        }

        long[] totals = computeTotals(items, userId);
        if (FileNodeConstants.SOURCE_LOCAL.equals(targetType)) {
            validateQuota(userId, totals[1]);
        }

        TransferTask task = new TransferTask();
        task.setId(IdUtil.nextId());
        task.setUserId(userId);
        task.setOpType(opType);
        task.setSourceType(sourceType);
        task.setTargetType(targetType);
        task.setSourceMountId(sourceMountId);
        task.setTargetMountId(targetMountId);
        task.setTargetParentId(targetParentId);
        task.setStatus(TransferTaskStatus.PENDING.getValue());
        task.setTotalCount(totals[0]);
        task.setSuccessCount(0L);
        task.setFailCount(0L);
        task.setTotalBytes(totals[1]);
        task.setItems(writeJson(items));
        transferTaskMapper.insert(task);

        eventPublisher.publishEvent(new TransferSubmittedEvent(this, task.getId()));
        return toVo(task);
    }

    @Override
    public TransferTaskVo getTask(String taskId, String userId) {
        return toVo(requireOwnedTask(taskId, userId));
    }

    @Override
    public List<TransferTaskVo> listRecent(String userId, int limit) {
        LambdaQueryWrapper<TransferTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TransferTask::getUserId, userId);
        wrapper.orderByDesc(TransferTask::getCreateTime);
        wrapper.last("LIMIT " + Math.max(1, Math.min(limit, 50)));
        return transferTaskMapper.selectList(wrapper).stream().map(this::toVo).toList();
    }

    @Override
    public void cancel(String taskId, String userId) {
        TransferTask task = requireOwnedTask(taskId, userId);
        if (TransferTaskStatus.isTerminal(task.getStatus())) {
            return;
        }
        TransferTask update = new TransferTask();
        update.setId(task.getId());
        update.setUpdateTime(LocalDateTime.now());
        if (TransferTaskStatus.PENDING.getValue().equals(task.getStatus())) {
            update.setStatus(TransferTaskStatus.CANCELED.getValue());
            update.setEndTime(LocalDateTime.now());
        } else {
            update.setStatus(TransferTaskStatus.CANCELLING.getValue());
        }
        transferTaskMapper.updateById(update);
    }

    @Override
    public TransferTaskVo waitTerminal(String taskId, String userId) {
        while (true) {
            TransferTask task = requireOwnedTask(taskId, userId);
            if (TransferTaskStatus.isTerminal(task.getStatus())) {
                return toVo(task);
            }
            try {
                Thread.sleep(WAIT_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "等待传输完成被中断");
            }
        }
    }

    /**
     * 构建传输项快照：逐项做同名冲突解决（基于目标端本地镜像），跳过的项不入任务。
     */
    private List<TransferItem> buildItems(FileExecuteOperationDto dto, List<FileNode> sources,
                                          String targetParentId, String userId) {
        ConflictStrategy globalStrategy = ConflictStrategy.fromCode(dto.getGlobalStrategy());
        List<TransferItem> items = new ArrayList<>();
        for (int i = 0; i < dto.getItems().size(); i++) {
            OperationItemDto operationItem = dto.getItems().get(i);
            FileNode source = sources.get(i);
            String targetName = StringUtils.hasText(operationItem.getNewName())
                    ? operationItem.getNewName().trim() : source.getName();
            ConflictStrategy strategy = ConflictStrategy.fromCode(operationItem.getStrategy());
            if (strategy == null) {
                strategy = globalStrategy != null ? globalStrategy : ConflictStrategy.KEEP;
            }
            FileNode existing = FileConflictHelper.findSameName(fileMapper, userId, targetParentId, targetName);
            FileConflictResolver.ConflictResolution resolution =
                    conflictResolver.resolveName(userId, targetParentId, targetName, existing, strategy);
            if (resolution.skipped()) {
                continue;
            }
            TransferItem item = new TransferItem();
            item.setNodeId(source.getId());
            item.setName(source.getName());
            item.setType(source.getType());
            item.setSize(source.getSize());
            item.setFinalName(resolution.finalName());
            item.setStrategy(strategy.getCode());
            item.setReplaceExistingId(resolution.existingToReplace() == null
                    ? null : resolution.existingToReplace().getId());
            items.add(item);
        }
        return items;
    }

    /**
     * 统计传输项的文件总数与总字节（文件夹按本地镜像子树展开）。
     *
     * @return 二元组：文件数、总字节
     */
    private long[] computeTotals(List<TransferItem> items, String userId) {
        long count = 0L;
        long bytes = 0L;
        for (TransferItem item : items) {
            if (FileNodeConstants.TYPE_FILE.equals(item.getType())) {
                count++;
                bytes += item.getSize() == null ? 0L : item.getSize();
                continue;
            }
            FileNode folder = fileMapper.selectById(item.getNodeId());
            if (folder == null) {
                continue;
            }
            List<FileNode> descendants = fileMapper.selectByIdPathPrefix(userId, folder.getPath(), folder.getId());
            for (FileNode descendant : descendants) {
                if (FileNodeConstants.TYPE_FILE.equals(descendant.getType())) {
                    count++;
                    bytes += descendant.getSize() == null ? 0L : descendant.getSize();
                }
            }
        }
        return new long[]{count, bytes};
    }

    /**
     * 远程 → 本地传输前校验用户配额与存储空间容量。
     */
    private void validateQuota(String userId, long totalBytes) {
        if (totalBytes <= 0) {
            return;
        }
        User user = userSpaceSupport.requireUser(userId);
        StorageSpace space = userSpaceSupport.requireSpace(user);
        long used = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        long quota = user.getQuota() == null ? 0L : user.getQuota();
        if (quota > 0 && used + totalBytes > quota) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户配额不足，无法完成传输");
        }
        long spaceUsed = space.getUsedSpace() == null ? 0L : space.getUsedSpace();
        long capacity = space.getCapacity() == null ? 0L : space.getCapacity();
        if (capacity > 0 && spaceUsed + totalBytes > capacity) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "存储空间容量不足，无法完成传输");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "序列化传输项失败", e);
        }
    }

    private TransferTask requireOwnedTask(String taskId, String userId) {
        TransferTask task = transferTaskMapper.selectById(taskId);
        if (task == null || !userId.equals(task.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "传输任务不存在");
        }
        return task;
    }

    private TransferTaskVo toVo(TransferTask task) {
        TransferTaskVo vo = new TransferTaskVo();
        vo.setId(task.getId());
        vo.setOpType(task.getOpType());
        vo.setSourceType(task.getSourceType());
        vo.setTargetType(task.getTargetType());
        vo.setStatus(task.getStatus());
        vo.setTotalCount(task.getTotalCount());
        vo.setSuccessCount(task.getSuccessCount());
        vo.setFailCount(task.getFailCount());
        vo.setTotalBytes(task.getTotalBytes());
        vo.setFailDetail(task.getFailDetail());
        vo.setErrorMsg(task.getErrorMsg());
        vo.setStartTime(task.getStartTime());
        vo.setEndTime(task.getEndTime());
        vo.setCreateTime(task.getCreateTime());
        return vo;
    }
}
