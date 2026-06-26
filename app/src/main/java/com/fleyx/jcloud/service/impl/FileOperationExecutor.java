package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.OperationItemDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.util.FileConflictHelper;
import com.fleyx.jcloud.util.FileConflictOverwriteHandler;
import com.fleyx.jcloud.util.FileConflictResolver;
import com.fleyx.jcloud.util.FileLinkUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文件移动/复制执行器。
 */
@Component
@RequiredArgsConstructor
public class FileOperationExecutor {

    private static final String TYPE_FILE = "file";
    private static final String TYPE_FOLDER = "folder";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_SKIPPED = "skipped";

    private final FileMapper fileMapper;
    private final UserMapper userMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final FileConflictResolver conflictResolver;
    private final FileConflictOverwriteHandler overwriteHandler;

    /**
     * 执行单条移动。
     *
     * @param item                 操作项
     * @param userId               用户 ID
     * @param targetParentId       目标父节点 ID
     * @param targetParentPathName 目标父节点 pathName
     * @return 操作结果
     */
    public OperationOutcome moveItem(OperationItemDto item, Long userId,
                                     Long targetParentId, String targetParentPathName) {
        FileNode source = getOwnedNode(item.getId(), userId);
        String targetName = resolveTargetName(item, source);
        ConflictStrategy strategy = resolveStrategy(item);

        validateNotMoveToSelfSubtree(source, targetParentId, userId);

        FileNode existing = FileConflictHelper.findSameName(fileMapper, userId, targetParentId, targetName);
        if (existing != null && TYPE_FOLDER.equals(source.getType()) && TYPE_FOLDER.equals(existing.getType())) {
            moveFolderContents(source, existing, userId);
            return OperationOutcome.success(source, source.getName());
        }

        FileConflictResolver.ConflictResolution resolution =
                conflictResolver.resolveName(userId, targetParentId, targetName, existing, strategy);
        if (resolution.skipped()) {
            return OperationOutcome.skipped(source);
        }

        StorageSpace space = storageSpaceMapper.selectById(source.getStorageSpaceId());
        User user = userMapper.selectById(userId);
        applyOverwriteIfNeeded(resolution, user);

        String newPathName = resolveNodePathName(targetParentPathName, targetName, source.getType());
        if (TYPE_FILE.equals(source.getType())) {
            Path sourcePath = FilePathUtil.resolvePhysicalPath(source, space);
            Path targetPath = FilePathUtil.resolvePhysicalPath(space, userId, newPathName, resolution.finalName());
            movePhysicalFile(sourcePath, targetPath);
        } else {
            updateFolderPathName(source, newPathName);
        }

        source.setParentId(targetParentId);
        source.setName(resolution.finalName());
        source.setPathName(newPathName);
        source.setPath(newPathName);
        fileMapper.updateById(source);
        return OperationOutcome.success(source, resolution.finalName());
    }

    /**
     * 执行单条复制。
     *
     * @param item                 操作项
     * @param userId               用户 ID
     * @param targetParentId       目标父节点 ID
     * @param targetParentPathName 目标父节点 pathName
     * @param user                 用户对象（用于配额更新）
     * @return 操作结果
     */
    public OperationOutcome copyItem(OperationItemDto item, Long userId,
                                     Long targetParentId, String targetParentPathName,
                                     User user) {
        FileNode source = getOwnedNode(item.getId(), userId);
        String targetName = resolveTargetName(item, source);
        ConflictStrategy strategy = resolveStrategy(item);

        validateNotMoveToSelfSubtree(source, targetParentId, userId);

        FileNode existing = FileConflictHelper.findSameName(fileMapper, userId, targetParentId, targetName);
        if (existing != null && TYPE_FOLDER.equals(source.getType()) && TYPE_FOLDER.equals(existing.getType())) {
            copyFolderContents(source, existing, user);
            return OperationOutcome.success(existing, existing.getName());
        }

        FileConflictResolver.ConflictResolution resolution =
                conflictResolver.resolveName(userId, targetParentId, targetName, existing, strategy);
        if (resolution.skipped()) {
            return OperationOutcome.skipped(source);
        }

        applyOverwriteIfNeeded(resolution, user);
        String newPathName = resolveNodePathName(targetParentPathName, targetName, source.getType());
        StorageSpace space = storageSpaceMapper.selectById(source.getStorageSpaceId());
        FileNode copied = copyNodeRecursively(source, targetParentId, newPathName, user, space);
        return OperationOutcome.success(copied, copied.getName());
    }

    private ConflictStrategy resolveStrategy(OperationItemDto item) {
        ConflictStrategy strategy = ConflictStrategy.fromCode(item.getStrategy());
        return strategy == null ? ConflictStrategy.KEEP : strategy;
    }

    private void applyOverwriteIfNeeded(FileConflictResolver.ConflictResolution resolution, User user) {
        if (resolution.existingToReplace() != null) {
            overwriteHandler.deleteExistingForOverwrite(resolution.existingToReplace(), user);
        }
    }

    private void validateNotMoveToSelfSubtree(FileNode source, Long targetParentId, Long userId) {
        if (!TYPE_FOLDER.equals(source.getType())) {
            return;
        }
        if (targetParentId.equals(source.getId())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能将文件夹移动到自身内部");
        }
        FileNode targetParent = fileMapper.selectById(targetParentId);
        if (targetParent == null || !userId.equals(targetParent.getUserId())) {
            return;
        }
        String sourcePrefix = source.getPathName().endsWith("/") ? source.getPathName() : source.getPathName() + "/";
        if (targetParent.getPathName().startsWith(sourcePrefix) || targetParent.getPathName().equals(source.getPathName())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能将文件夹移动到自身子目录");
        }
    }

    private void moveFolderContents(FileNode sourceFolder, FileNode targetFolder, Long userId) {
        List<FileNode> children = fileMapper.selectList(
                new LambdaQueryWrapper<FileNode>()
                        .eq(FileNode::getParentId, sourceFolder.getId())
                        .eq(FileNode::getDeleteAt, 0L));
        for (FileNode child : children) {
            OperationItemDto childItem = new OperationItemDto();
            childItem.setId(child.getId());
            childItem.setName(child.getName());
            childItem.setStrategy(ConflictStrategy.KEEP.getCode());
            moveItem(childItem, userId, targetFolder.getId(), targetFolder.getPathName());
        }
    }

    private void copyFolderContents(FileNode sourceFolder, FileNode targetFolder, User user) {
        List<FileNode> children = fileMapper.selectList(
                new LambdaQueryWrapper<FileNode>()
                        .eq(FileNode::getParentId, sourceFolder.getId())
                        .eq(FileNode::getDeleteAt, 0L));
        for (FileNode child : children) {
            OperationItemDto childItem = new OperationItemDto();
            childItem.setId(child.getId());
            childItem.setName(child.getName());
            childItem.setStrategy(ConflictStrategy.KEEP.getCode());
            copyItem(childItem, user.getId(), targetFolder.getId(), targetFolder.getPathName(), user);
        }
    }

    private FileNode copyNodeRecursively(FileNode source, Long parentId,
                                         String pathName, User user, StorageSpace space) {
        FileNode target = new FileNode();
        target.setUserId(source.getUserId());
        target.setParentId(parentId);
        target.setName(source.getName());
        target.setType(source.getType());
        target.setSize(source.getSize());
        target.setHash(source.getHash());
        target.setStorageSpaceId(source.getStorageSpaceId());
        target.setPathName(pathName);
        target.setPath(pathName);
        target.setMimeType(source.getMimeType());
        target.setStatus(1);
        fileMapper.insert(target);

        if (TYPE_FILE.equals(source.getType())) {
            copyPhysicalFile(source, target, user, space);
        } else {
            List<FileNode> children = fileMapper.selectList(
                    new LambdaQueryWrapper<FileNode>()
                            .eq(FileNode::getParentId, source.getId())
                            .eq(FileNode::getDeleteAt, 0L));
            for (FileNode child : children) {
                String childPathName = resolveNodePathName(pathName, child.getName(), child.getType());
                copyNodeRecursively(child, target.getId(), childPathName, user, space);
            }
        }
        return target;
    }

    private void copyPhysicalFile(FileNode source, FileNode target, User user, StorageSpace space) {
        Path sourcePath = FilePathUtil.resolvePhysicalPath(source, space);
        Path targetPath = FilePathUtil.resolvePhysicalPath(target, space);
        try {
            Files.createDirectories(targetPath.getParent());
            FileLinkUtil.linkOrCopy(sourcePath, targetPath);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "文件复制失败");
        }
        long size = source.getSize() == null ? 0L : source.getSize();
        updateUsedSpace(user, size);
    }

    private void movePhysicalFile(Path sourcePath, Path targetPath) {
        try {
            Files.createDirectories(targetPath.getParent());
            Files.move(sourcePath, targetPath);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "文件移动失败");
        }
    }

    private void updateFolderPathName(FileNode folder, String newPathName) {
        String oldPathName = folder.getPathName();
        String oldPrefix = oldPathName.endsWith("/") ? oldPathName : oldPathName + "/";
        String newPrefix = newPathName.endsWith("/") ? newPathName : newPathName + "/";

        List<FileNode> descendants = fileMapper.selectList(
                new LambdaQueryWrapper<FileNode>()
                        .eq(FileNode::getUserId, folder.getUserId())
                        .likeRight(FileNode::getPathName, oldPathName)
                        .eq(FileNode::getDeleteAt, 0L));
        for (FileNode node : descendants) {
            if (node.getId().equals(folder.getId())) {
                continue;
            }
            String updated = node.getPathName();
            if (updated.equals(oldPathName)) {
                updated = newPathName;
            } else if (updated.startsWith(oldPrefix)) {
                updated = newPrefix + updated.substring(oldPrefix.length());
            }
            node.setPathName(updated);
            node.setPath(updated);
            fileMapper.updateById(node);
        }
        folder.setPathName(newPathName);
        folder.setPath(newPathName);
    }

    private FileNode getOwnedNode(Long nodeId, Long userId) {
        if (nodeId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "节点 ID 不能为空");
        }
        FileNode node = fileMapper.selectById(nodeId);
        if (node == null || !userId.equals(node.getUserId()) || node.getDeleteAt() != 0L) {
            throw new BusinessException(ResultCode.NOT_FOUND, "节点不存在");
        }
        return node;
    }

    private String resolveTargetName(OperationItemDto item, FileNode source) {
        return item.getNewName() != null && !item.getNewName().isBlank()
                ? item.getNewName().trim()
                : source.getName();
    }

    private String resolveNodePathName(String parentPathName, String name, String type) {
        if (TYPE_FILE.equals(type)) {
            return parentPathName;
        }
        return FilePathUtil.buildPathName(parentPathName, name);
    }

    private void updateUsedSpace(User user, long delta) {
        long used = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        long quota = user.getQuota() == null ? 0L : user.getQuota();
        if (used + delta > quota) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户配额不足");
        }
        user.setUsedSpace(used + delta);
        userMapper.updateById(user);
    }

    /**
     * 操作结果内部对象。
     */
    public record OperationOutcome(String status, Long sourceId, String sourceName,
                                    String newName, Long nodeId) {

        static OperationOutcome success(FileNode node, String newName) {
            return new OperationOutcome(STATUS_SUCCESS, node.getId(), node.getName(), newName, node.getId());
        }

        static OperationOutcome skipped(FileNode node) {
            return new OperationOutcome(STATUS_SKIPPED, node.getId(), node.getName(), null, null);
        }
    }
}
