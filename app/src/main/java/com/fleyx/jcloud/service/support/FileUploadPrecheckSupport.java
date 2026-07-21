package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.BatchUploadErrorCode;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.convert.FileConvert;
import com.fleyx.jcloud.model.dto.FileUploadPreCheckDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.BatchUploadPreCheckItemVo;
import com.fleyx.jcloud.model.vo.ConflictItemVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.UploadPreCheckVo;
import com.fleyx.jcloud.service.FolderPathService;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.util.BatchUploadHelper;
import com.fleyx.jcloud.util.FileConflictHelper;
import com.fleyx.jcloud.util.FileNodeUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文件上传预检支撑组件。
 */
@Component
@RequiredArgsConstructor
public class FileUploadPrecheckSupport {

    private static final String TYPE_FILE = "file";

    private final FileMapper fileMapper;
    private final FileConvert fileConvert;
    private final FolderPathService folderPathService;
    private final RemoteFileService remoteFileService;
    private final FileNodeSupport fileNodeSupport;
    private final UserSpaceSupport userSpaceSupport;

    /**
     * 批量上传预检，单项失败不影响其他项。
     *
     * @param items  预检参数列表
     * @param userId 用户 ID
     * @return 批量预检结果项列表
     */
    public List<BatchUploadPreCheckItemVo> doBatchPreCheckUpload(List<FileUploadPreCheckDto> items, String userId) {
        BatchUploadHelper.validateBatchItems(items, 150, "预检");
        checkBatchQuota(items, userId);

        List<BatchUploadPreCheckItemVo> results = new ArrayList<>(items.size());
        Set<String> clientFileIds = new HashSet<>();
        Set<String> processedPaths = new HashSet<>();

        for (FileUploadPreCheckDto item : items) {
            String clientFileId = item.getClientFileId();
            BatchUploadPreCheckItemVo result = new BatchUploadPreCheckItemVo();
            result.setClientFileId(clientFileId);

            BatchUploadErrorCode clientIdError = BatchUploadHelper.validateClientFileId(clientFileId, clientFileIds);
            if (clientIdError != null) {
                BatchUploadHelper.fillError(result, clientIdError);
                results.add(result);
                continue;
            }

            try {
                String pathKey = BatchUploadHelper.buildPathKey(item.getParentId(), item.getRelativePath(), item.getFileName());
                if (!processedPaths.add(pathKey)) {
                    BatchUploadHelper.fillError(result, BatchUploadErrorCode.DUPLICATE_FILE_IN_BATCH);
                    results.add(result);
                    continue;
                }

                UploadPreCheckVo data = doPreCheckUpload(item, userId);
                result.setStatus("success");
                result.setData(data);
            } catch (BusinessException e) {
                BatchUploadHelper.fillError(result, BatchUploadHelper.mapErrorCode(e), e.getMessage());
            } catch (Exception e) {
                BatchUploadHelper.fillError(result, BatchUploadErrorCode.SYSTEM_ERROR, e.getMessage());
            }
            results.add(result);
        }
        return results;
    }

    /**
     * 单条上传预检。
     *
     * @param dto    预检参数
     * @param userId 用户 ID
     * @return 预检结果
     */
    public UploadPreCheckVo doPreCheckUpload(FileUploadPreCheckDto dto, String userId) {
        String parentId = FileNodeUtil.normalizeParentId(dto.getParentId());
        FileNode parentNode = fileNodeSupport.resolveParentNode(parentId, userId);

        if (parentNode != null && FileNodeConstants.SOURCE_REMOTE.equals(parentNode.getSourceType())) {
            if (StringUtils.hasText(dto.getRelativePath())) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "远程目录暂不支持文件夹上传");
            }
            return remoteFileService.preCheckUpload(dto, parentNode, userId);
        }

        fileNodeSupport.validateTargetParent(parentId, userId);

        String finalParentId = parentId;
        String finalFileName = dto.getFileName();
        if (StringUtils.hasText(dto.getRelativePath())) {
            finalParentId = folderPathService.resolveOrCreateFolderPath(userId, parentId, dto.getRelativePath());
            finalFileName = FilePathUtil.extractFileName(dto.getRelativePath());
        }

        UploadPreCheckVo result = new UploadPreCheckVo();
        result.setConflicts(buildConflictItems(finalFileName, dto.getSize(), userId, finalParentId));
        result.setCandidates(buildInstantCandidates(finalFileName, dto.getPartialHash(), userId));
        return result;
    }

    private List<ConflictItemVo> buildConflictItems(String fileName, Long size, String userId, String parentId) {
        FileNode existing = FileConflictHelper.findSameName(fileMapper, userId, parentId, fileName);
        if (existing == null) {
            return List.of();
        }
        ConflictItemVo vo = new ConflictItemVo();
        vo.setNodeId(existing.getId());
        vo.setSourceName(fileName);
        vo.setExistingId(existing.getId());
        vo.setExistingName(existing.getName());
        vo.setExistingType(existing.getType());
        vo.setType(existing.getType());
        vo.setSuggestedStrategy(ConflictStrategy.KEEP.getCode());
        return List.of(vo);
    }

    private List<FileNodeVo> buildInstantCandidates(String fileName, String partialHash, String userId) {
        if (!StringUtils.hasText(partialHash)) {
            return List.of();
        }
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileNode::getUserId, userId);
        wrapper.eq(FileNode::getHash, partialHash);
        wrapper.eq(FileNode::getType, TYPE_FILE);
        wrapper.and(w -> w.eq(FileNode::getSourceType, FileNodeConstants.SOURCE_LOCAL)
                .or()
                .isNull(FileNode::getSourceType));
        wrapper.orderByDesc(FileNode::getCreateTime);
        return fileMapper.selectList(wrapper).stream()
                .map(fileConvert::poToVo)
                .toList();
    }

    private void checkBatchQuota(List<FileUploadPreCheckDto> items, String userId) {
        long totalSize = items.stream()
                .filter(dto -> !isRemoteParent(FileNodeUtil.normalizeParentId(dto.getParentId()), userId))
                .mapToLong(dto -> dto.getSize() == null ? 0L : dto.getSize())
                .sum();
        if (totalSize <= 0) {
            return;
        }
        User user = userSpaceSupport.requireUser(userId);
        long usedSpace = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        long quota = user.getQuota() == null ? 0L : user.getQuota();
        if (quota > 0 && usedSpace + totalSize > quota) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户配额不足");
        }
    }

    private boolean isRemoteParent(String parentId, String userId) {
        if (FileNodeConstants.ROOT_ID.equals(parentId)) {
            return false;
        }
        FileNode parent = fileMapper.selectById(parentId);
        return parent != null && userId.equals(parent.getUserId())
                && FileNodeConstants.SOURCE_REMOTE.equals(parent.getSourceType());
    }
}
