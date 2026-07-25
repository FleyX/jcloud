package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.dto.OperationItemDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.OperationResultVo;
import com.fleyx.jcloud.service.RemoteFileOperationService;
import com.fleyx.jcloud.service.impl.FileOperationExecutor;
import com.fleyx.jcloud.util.FileConflictHelper;
import com.fleyx.jcloud.util.FileConflictResolver;
import com.fleyx.jcloud.util.FileNodeUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 文件移动/复制批量编排支撑组件。
 */
@Component
@RequiredArgsConstructor
public class FileMoveCopySupport {

    private static final String TYPE_FOLDER = "folder";

    private final FileMapper fileMapper;
    private final UserMapper userMapper;
    private final FileOperationExecutor executor;
    private final RemoteFileOperationService remoteFileOperationService;
    private final FileConflictResolver conflictResolver;
    private final FileNodeSupport fileNodeSupport;
    private final FilePathSupport filePathSupport;

    /**
     * 批量移动，整个列表在一个事务内执行。
     *
     * @param dto    操作参数
     * @param userId 用户 ID
     * @return 操作结果列表
     */
    @Transactional(rollbackFor = Exception.class)
    public List<OperationResultVo> doMove(FileExecuteOperationDto dto, String userId) {
        String targetParentId = FileNodeUtil.normalizeParentId(dto.getTargetParentId());
        FileNode targetParent = resolveTargetParentNode(targetParentId, userId);
        List<FileNode> sources = dto.getItems().stream()
                .map(item -> fileNodeSupport.getOwnedNode(item.getId(), userId))
                .toList();
        validateSourceConsistency(sources, targetParent);
        validateTargetNotSelfOrDescendant(sources, targetParent, targetParentId);

        String targetParentPathName = resolveParentPathName(targetParentId, userId);
        ConflictStrategy globalStrategy = ConflictStrategy.fromCode(dto.getGlobalStrategy());
        List<OperationResultVo> results = new ArrayList<>();
        for (OperationItemDto item : dto.getItems()) {
            FileNode source = fileNodeSupport.getOwnedNode(item.getId(), userId);
            if (FileNodeConstants.SOURCE_REMOTE.equals(source.getSourceType())) {
                results.add(doRemoteMove(item, source, targetParent, userId, globalStrategy));
            } else {
                fillDefaultStrategy(item, globalStrategy);
                FileOperationExecutor.OperationOutcome outcome = executor.moveItem(
                        item, userId, targetParentId, targetParentPathName);
                results.add(toResultVo(outcome));
            }
        }
        return results;
    }

    /**
     * 批量复制，整个列表在一个事务内执行。
     *
     * @param dto    操作参数
     * @param userId 用户 ID
     * @return 操作结果列表
     */
    @Transactional(rollbackFor = Exception.class)
    public List<OperationResultVo> doCopy(FileExecuteOperationDto dto, String userId) {
        String targetParentId = FileNodeUtil.normalizeParentId(dto.getTargetParentId());
        FileNode targetParent = resolveTargetParentNode(targetParentId, userId);
        List<FileNode> sources = dto.getItems().stream()
                .map(item -> fileNodeSupport.getOwnedNode(item.getId(), userId))
                .toList();
        validateSourceConsistency(sources, targetParent);
        validateTargetNotSelfOrDescendant(sources, targetParent, targetParentId);

        String targetParentPathName = resolveParentPathName(targetParentId, userId);
        User user = userMapper.selectById(userId);
        ConflictStrategy globalStrategy = ConflictStrategy.fromCode(dto.getGlobalStrategy());
        List<OperationResultVo> results = new ArrayList<>();
        for (OperationItemDto item : dto.getItems()) {
            FileNode source = fileNodeSupport.getOwnedNode(item.getId(), userId);
            if (FileNodeConstants.SOURCE_REMOTE.equals(source.getSourceType())) {
                results.add(doRemoteCopy(item, source, targetParent, userId, globalStrategy));
            } else {
                fillDefaultStrategy(item, globalStrategy);
                FileOperationExecutor.OperationOutcome outcome = executor.copyItem(
                        item, userId, targetParentId, targetParentPathName, user);
                results.add(toResultVo(outcome));
            }
        }
        return results;
    }

    private OperationResultVo doRemoteMove(OperationItemDto item, FileNode source,
                                           FileNode targetParent, String userId,
                                           ConflictStrategy globalStrategy) {
        String targetName = StringUtils.hasText(item.getNewName()) ? item.getNewName().trim() : source.getName();
        ConflictStrategy strategy = resolveItemStrategy(item, globalStrategy);
        FileNode existing = FileConflictHelper.findSameName(fileMapper, userId, targetParent.getId(), targetName);
        FileConflictResolver.ConflictResolution resolution =
                conflictResolver.resolveName(userId, targetParent.getId(), targetName, existing, strategy);
        if (resolution.skipped()) {
            OperationResultVo vo = new OperationResultVo();
            vo.setSourceId(source.getId());
            vo.setSourceName(source.getName());
            vo.setStatus(FileNodeConstants.STATUS_SKIPPED);
            return vo;
        }
        if (resolution.existingToReplace() != null) {
            remoteFileOperationService.delete(resolution.existingToReplace(), userId);
        }
        FileNodeVo moved = remoteFileOperationService.move(source, targetParent, resolution.finalName(), userId);
        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(source.getId());
        vo.setSourceName(source.getName());
        vo.setStatus(FileNodeConstants.STATUS_SUCCESS);
        vo.setNewName(resolution.finalName().equals(source.getName()) ? null : resolution.finalName());
        vo.setNodeId(moved.getId());
        return vo;
    }

    private OperationResultVo doRemoteCopy(OperationItemDto item, FileNode source,
                                           FileNode targetParent, String userId,
                                           ConflictStrategy globalStrategy) {
        throw new BusinessException(ResultCode.BUSINESS_ERROR, "远程文件暂不支持复制");
    }

    private ConflictStrategy resolveItemStrategy(OperationItemDto item, ConflictStrategy globalStrategy) {
        ConflictStrategy strategy = ConflictStrategy.fromCode(item.getStrategy());
        if (strategy == null && globalStrategy != null) {
            strategy = globalStrategy;
        }
        return strategy;
    }

    private void fillDefaultStrategy(OperationItemDto item, ConflictStrategy globalStrategy) {
        if (!StringUtils.hasText(item.getStrategy()) && globalStrategy != null) {
            item.setStrategy(globalStrategy.getCode());
        }
        if (!StringUtils.hasText(item.getStrategy())) {
            item.setStrategy(ConflictStrategy.KEEP.getCode());
        }
    }

    private OperationResultVo toResultVo(FileOperationExecutor.OperationOutcome outcome) {
        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(outcome.sourceId());
        vo.setSourceName(outcome.sourceName());
        vo.setStatus(outcome.status());
        vo.setNewName(outcome.newName());
        vo.setNodeId(outcome.nodeId());
        return vo;
    }

    /**
     * 解析目标父节点名称路径，根目录返回 "/"。
     *
     * @param parentId 父节点 ID
     * @param userId   用户 ID
     * @return 名称路径
     */
    public String resolveParentPathName(String parentId, String userId) {
        if (FileNodeConstants.ROOT_ID.equals(parentId)) {
            return "/";
        }
        FileNode parent = fileNodeSupport.getOwnedNode(parentId, userId);
        if (!TYPE_FOLDER.equals(parent.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "目标父节点不是文件夹");
        }
        return filePathSupport.resolveNamePath(parent, userId);
    }

    /**
     * 解析目标父文件夹节点，根目录返回 {@code null}。
     *
     * @param targetParentId 目标父节点 ID
     * @param userId         用户 ID
     * @return 目标父文件夹节点，根目录为 {@code null}
     */
    public FileNode resolveTargetParentNode(String targetParentId, String userId) {
        if (FileNodeConstants.ROOT_ID.equals(targetParentId)) {
            return null;
        }
        FileNode parent = fileNodeSupport.getOwnedNode(targetParentId, userId);
        if (!TYPE_FOLDER.equals(parent.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "目标父节点不是文件夹");
        }
        return parent;
    }

    /**
     * 校验批量源节点之间来源一致（同一来源类型且同一远程挂载点）。
     *
     * @param sources 源节点列表
     */
    public void validateSourcesHomogeneous(List<FileNode> sources) {
        if (sources == null || sources.isEmpty()) {
            return;
        }
        String firstSource = sources.get(0).getSourceType() == null
                ? FileNodeConstants.SOURCE_LOCAL : sources.get(0).getSourceType();
        String firstMountId = sources.get(0).getRemoteMountId();
        for (FileNode source : sources) {
            String sourceType = source.getSourceType() == null
                    ? FileNodeConstants.SOURCE_LOCAL : source.getSourceType();
            if (!firstSource.equals(sourceType)) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能混合本地与远程文件一起操作");
            }
            if (FileNodeConstants.SOURCE_REMOTE.equals(sourceType)
                    && !Objects.equals(firstMountId, source.getRemoteMountId())) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能混合不同远程挂载点的文件一起操作");
            }
        }
    }

    /**
     * 校验源节点与目标父节点的本地/远程来源一致性。
     * <p>
     * 跨来源复制/移动需通过跨来源传输任务（/jcloud/api/transfers）执行。
     *
     * @param sources      源节点列表
     * @param targetParent 目标父节点
     */
    public void validateSourceConsistency(List<FileNode> sources, FileNode targetParent) {
        String targetSource = targetParent == null || targetParent.getSourceType() == null
                ? FileNodeConstants.SOURCE_LOCAL
                : targetParent.getSourceType();
        String targetMountId = targetParent == null ? null : targetParent.getRemoteMountId();
        for (FileNode source : sources) {
            String sourceSource = source.getSourceType() == null
                    ? FileNodeConstants.SOURCE_LOCAL
                    : source.getSourceType();
            if (!targetSource.equals(sourceSource)
                    || (FileNodeConstants.SOURCE_REMOTE.equals(sourceSource)
                    && !Objects.equals(targetMountId, source.getRemoteMountId()))) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "跨来源复制/移动请通过传输任务执行");
            }
        }
    }

    /**
     * 校验移动/复制目标不能是源节点自身或其子目录。
     *
     * @param sources        源节点列表
     * @param targetParent   目标父节点
     * @param targetParentId 目标父节点 ID
     */
    public void validateTargetNotSelfOrDescendant(List<FileNode> sources, FileNode targetParent,
                                                  String targetParentId) {
        for (FileNode source : sources) {
            if (source.getId().equals(targetParentId)) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能移动到自身或其子目录");
            }
            if (targetParent != null && pathContainsId(targetParent.getPath(), source.getId())) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能移动到自身或其子目录");
            }
        }
    }

    private boolean pathContainsId(String path, String id) {
        if (!StringUtils.hasText(path) || !StringUtils.hasText(id)) {
            return false;
        }
        for (String part : path.split("\\.")) {
            if (id.equals(part)) {
                return true;
            }
        }
        return false;
    }
}
