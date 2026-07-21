package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 回收站恢复目标位置解析支撑组件。
 * <p>
 * 负责解析恢复目标父节点（原父目录已删除时按需补建目录链）及其名称路径。
 */
@Component
@RequiredArgsConstructor
public class TrashRestorePathSupport {

    private final FileMapper fileMapper;
    private final FileNodeSupport fileNodeSupport;
    private final FilePathSupport filePathSupport;

    /**
     * 解析恢复目标父节点 ID，原父目录已删除时按需补建目录链。
     *
     * @param record 回收站记录
     * @param userId 用户 ID
     * @return 目标父节点 ID
     */
    public String resolveRestoreParentId(RecycleRecord record, String userId) {
        String parentPathName = FilePathUtil.parentOf(record.getOriginalPathName());
        if ("/".equals(parentPathName) || parentPathName.isBlank()) {
            return FileNodeConstants.ROOT_ID;
        }
        FileNode parent = fileNodeSupport.findFolderByPathName(userId, parentPathName);
        if (parent != null) {
            return parent.getId();
        }
        return createMissingParentChain(parentPathName, userId);
    }

    private String createMissingParentChain(String parentPathName, String userId) {
        if (parentPathName == null || "/".equals(parentPathName) || parentPathName.isBlank()) {
            return FileNodeConstants.ROOT_ID;
        }
        String clean = FilePathUtil.stripLeadingSlash(parentPathName);
        String[] parts = clean.split("/");
        String currentParentId = FileNodeConstants.ROOT_ID;
        for (String part : parts) {
            FileNode existing = fileNodeSupport.findFolderByName(userId, currentParentId, part);
            if (existing != null) {
                currentParentId = existing.getId();
                continue;
            }
            FileNode folder = fileNodeSupport.buildFolderNode(userId, currentParentId, part);
            fileNodeSupport.setNodePath(folder, currentParentId);
            fileMapper.insert(folder);
            currentParentId = folder.getId();
        }
        return currentParentId;
    }

    /**
     * 解析恢复目标父节点的名称路径。
     *
     * @param parentId 父节点 ID
     * @param userId   用户 ID
     * @return 名称路径
     */
    public String resolveRestorePathName(String parentId, String userId) {
        if (FileNodeConstants.ROOT_ID.equals(parentId)) {
            return "/";
        }
        FileNode parent = fileMapper.selectById(parentId);
        if (parent == null || !parent.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "父目录不存在");
        }
        return filePathSupport.resolveNamePath(parent, userId);
    }
}
