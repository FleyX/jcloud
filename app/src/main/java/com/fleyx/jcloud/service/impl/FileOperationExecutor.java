package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * @param targetParentPathName 目标父节点 namePath
     * @return 操作结果
     */
    public OperationOutcome moveItem(OperationItemDto item, String userId,
                                     String targetParentId, String targetParentPathName) {
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
        String username = user.getUsername();
        applyOverwriteIfNeeded(resolution, user);

        String newPathName = FilePathUtil.buildPathName(targetParentPathName, resolution.finalName());
        if (TYPE_FILE.equals(source.getType())) {
            FilePathUtil.ResolveContext ctx = buildResolveContext(source, username, space);
            Path sourcePath = FilePathUtil.resolvePhysicalPath(source, ctx);
            Path targetPath = FilePathUtil.resolvePhysicalPath(space, username, newPathName);
            movePhysicalFile(sourcePath, targetPath);
        } else {
            String oldPathName = resolveNamePath(source, userId);
            Path oldPhysicalPath = FilePathUtil.resolvePhysicalPath(space, username, oldPathName);
            Path newPhysicalPath = FilePathUtil.resolvePhysicalPath(space, username, newPathName);
            movePhysicalFile(oldPhysicalPath, newPhysicalPath);
            updateFolderPath(source, targetParentId, resolution.finalName());
        }

        source.setParentId(targetParentId);
        source.setName(resolution.finalName());
        setNodePath(source, targetParentId, targetParentPathName);
        fileMapper.updateById(source);
        return OperationOutcome.success(source, resolution.finalName());
    }

    /**
     * 执行单条复制。
     *
     * @param item                 操作项
     * @param userId               用户 ID
     * @param targetParentId       目标父节点 ID
     * @param targetParentPathName 目标父节点 namePath
     * @param user                 用户对象（用于配额更新）
     * @return 操作结果
     */
    public OperationOutcome copyItem(OperationItemDto item, String userId,
                                     String targetParentId, String targetParentPathName,
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
        String newPathName = FilePathUtil.buildPathName(targetParentPathName, resolution.finalName());
        StorageSpace space = storageSpaceMapper.selectById(source.getStorageSpaceId());
        String username = user.getUsername();
        FileNode copied = copyNodeRecursively(source, targetParentId, targetParentPathName,
                resolution.finalName(), user, space, username);
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

    private void validateNotMoveToSelfSubtree(FileNode source, String targetParentId, String userId) {
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
        String sourceFullPath = FilePathUtil.fullIdPath(source);
        String targetParentPath = targetParent.getPath();
        if (targetParentPath.equals(sourceFullPath)
                || targetParentPath.startsWith(sourceFullPath + FileNodeConstants.PATH_SEPARATOR)
                || (FileNodeConstants.ROOT_ID + FileNodeConstants.PATH_SEPARATOR + source.getId()).equals(targetParentPath)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能将文件夹移动到自身子目录");
        }
    }

    private void moveFolderContents(FileNode sourceFolder, FileNode targetFolder, String userId) {
        List<FileNode> children = fileMapper.selectByParentId(userId, sourceFolder.getId());
        String targetParentPathName = resolveNamePath(targetFolder, userId);
        for (FileNode child : children) {
            OperationItemDto childItem = new OperationItemDto();
            childItem.setId(child.getId());
            childItem.setName(child.getName());
            childItem.setStrategy(ConflictStrategy.KEEP.getCode());
            moveItem(childItem, userId, targetFolder.getId(), targetParentPathName);
        }
    }

    private void copyFolderContents(FileNode sourceFolder, FileNode targetFolder, User user) {
        List<FileNode> children = fileMapper.selectByParentId(user.getId(), sourceFolder.getId());
        String targetParentPathName = resolveNamePath(targetFolder, user.getId());
        for (FileNode child : children) {
            OperationItemDto childItem = new OperationItemDto();
            childItem.setId(child.getId());
            childItem.setName(child.getName());
            childItem.setStrategy(ConflictStrategy.KEEP.getCode());
            copyItem(childItem, user.getId(), targetFolder.getId(), targetParentPathName, user);
        }
    }

    private FileNode copyNodeRecursively(FileNode source, String parentId,
                                         String parentPathName, String name,
                                         User user, StorageSpace space, String username) {
        String newPathName = FilePathUtil.buildPathName(parentPathName, name);
        FileNode target = new FileNode();
        target.setUserId(source.getUserId());
        target.setParentId(parentId);
        target.setName(name);
        target.setType(source.getType());
        target.setSize(source.getSize());
        target.setHash(source.getHash());
        target.setStorageSpaceId(source.getStorageSpaceId());
        target.setMimeType(source.getMimeType());
        target.setStatus(1);
        setNodePath(target, parentId, parentPathName);
        fileMapper.insert(target);

        if (TYPE_FILE.equals(source.getType())) {
            copyPhysicalFile(source, target, user, space, username);
        } else {
            List<FileNode> children = fileMapper.selectByParentId(user.getId(), source.getId());
            for (FileNode child : children) {
                copyNodeRecursively(child, target.getId(), newPathName, child.getName(), user, space, username);
            }
        }
        return target;
    }

    private void copyPhysicalFile(FileNode source, FileNode target, User user, StorageSpace space, String username) {
        Set<String> ancestorIds = new HashSet<>(FilePathUtil.extractAncestorIds(List.of(source)));
        ancestorIds.addAll(FilePathUtil.extractAncestorIds(List.of(target)));
        Map<String, String> cache = queryAncestorNames(user.getId(), ancestorIds);
        FilePathUtil.ResolveContext ctx = FilePathUtil.contextOf(space, username, cache);
        Path sourcePath = FilePathUtil.resolvePhysicalPath(source, ctx);
        Path targetPath = FilePathUtil.resolvePhysicalPath(target, ctx);
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

    private void updateFolderPath(FileNode folder, String newParentId, String newName) {
        String oldFullPath = FilePathUtil.fullIdPath(folder);
        folder.setParentId(newParentId);
        folder.setName(newName);
        String newFullPath = computeNewFullPath(folder, newParentId, newName);

        List<FileNode> descendants = fileMapper.selectByIdPathPrefix(folder.getUserId(),
                folder.getPath(), folder.getId());
        for (FileNode node : descendants) {
            if (node.getId().equals(folder.getId())) {
                continue;
            }
            String updatedPath = node.getPath().replaceFirst(
                    java.util.regex.Pattern.quote(oldFullPath), newFullPath);
            node.setPath(updatedPath);
            fileMapper.updateById(node);
        }
        folder.setPath(newFullPath);
    }

    private String computeNewFullPath(FileNode folder, String newParentId, String newName) {
        if (FileNodeConstants.ROOT_ID.equals(newParentId)) {
            return folder.getId();
        }
        FileNode newParent = fileMapper.selectById(newParentId);
        if (newParent == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "目标父目录不存在");
        }
        return FilePathUtil.fullIdPath(newParent) + FileNodeConstants.PATH_SEPARATOR + folder.getId();
    }

    private void setNodePath(FileNode node, String parentId, String parentPathName) {
        if (FileNodeConstants.ROOT_ID.equals(parentId)) {
            node.setPath(FileNodeConstants.ROOT_ID);
        } else {
            FileNode parent = fileMapper.selectById(parentId);
            if (parent == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "目标父目录不存在");
            }
            node.setPath(FilePathUtil.fullIdPath(parent));
        }
    }

    private FileNode getOwnedNode(String nodeId, String userId) {
        if (nodeId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "节点 ID 不能为空");
        }
        FileNode node = fileMapper.selectById(nodeId);
        if (node == null || !userId.equals(node.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "节点不存在");
        }
        return node;
    }

    private String resolveTargetName(OperationItemDto item, FileNode source) {
        return item.getNewName() != null && !item.getNewName().isBlank()
                ? item.getNewName().trim()
                : source.getName();
    }

    private void updateUsedSpace(User user, long delta) {
        long used = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        long quota = user.getQuota() == null ? 0L : user.getQuota();
        if (quota > 0 && used + delta > quota) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户配额不足");
        }
        user.setUsedSpace(used + delta);
        userMapper.updateById(user);
    }

    private String resolveNamePath(FileNode node, String userId) {
        if (node == null) {
            return "/";
        }
        Set<String> ancestorIds = FilePathUtil.extractAncestorIds(List.of(node));
        Map<String, String> cache = queryAncestorNames(userId, ancestorIds);
        FilePathUtil.ResolveContext ctx = FilePathUtil.contextOf(null, userId, cache);
        return FilePathUtil.resolveNamePath(node, ctx);
    }

    private Map<String, String> queryAncestorNames(String userId, Set<String> ancestorIds) {
        Map<String, String> cache = new HashMap<>();
        if (ancestorIds.isEmpty()) {
            return cache;
        }
        List<FileNode> ancestors = fileMapper.selectBatchIds(ancestorIds);
        for (FileNode ancestor : ancestors) {
            if (userId.equals(ancestor.getUserId())) {
                cache.put(ancestor.getId(), ancestor.getName());
            }
        }
        return cache;
    }

    private FilePathUtil.ResolveContext buildResolveContext(FileNode node, String username, StorageSpace space) {
        String userId = node.getUserId();
        Set<String> ancestorIds = FilePathUtil.extractAncestorIds(List.of(node));
        Map<String, String> cache = queryAncestorNames(userId, ancestorIds);
        return FilePathUtil.contextOf(space, username, cache);
    }

    /**
     * 操作结果内部对象。
     */
    public record OperationOutcome(String status, String sourceId, String sourceName,
                                    String newName, String nodeId) {

        static OperationOutcome success(FileNode node, String newName) {
            return new OperationOutcome(STATUS_SUCCESS, node.getId(), node.getName(), newName, node.getId());
        }

        static OperationOutcome skipped(FileNode node) {
            return new OperationOutcome(STATUS_SKIPPED, node.getId(), node.getName(), null, null);
        }
    }
}
