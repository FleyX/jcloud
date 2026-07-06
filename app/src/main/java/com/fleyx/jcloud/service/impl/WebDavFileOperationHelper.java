package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.common.exception.WebDavException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.util.FileConflictHelper;
import com.fleyx.jcloud.util.FileHashUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.IdUtil;
import com.fleyx.jcloud.util.WebDavPathResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WebDAV 文件操作辅助类。
 * <p>
 * 封装上传、删除、移动、复制、创建文件夹等写操作，必须在调用前获取用户写锁。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebDavFileOperationHelper {

    private static final String TYPE_FILE = "file";
    private static final String TYPE_FOLDER = "folder";

    private final FileMapper fileMapper;
    private final UserMapper userMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final WebDavPathResolver pathResolver;

    /**
     * 上传或覆盖文件。
     *
     * @param userId 用户 ID
     * @param parent 父节点
     * @param name   文件名称
     * @param request HTTP 请求
     * @param size   文件大小
     */
    public void upload(String userId, FileNode parent, String name, HttpServletRequest request, long size) {
        FileNode existing = FileConflictHelper.findSameName(fileMapper, userId, parent.getId(), name);
        User user = requireUser(userId);
        StorageSpace space = requireSpace(user.getStorageSpaceId());
        long usedSpace = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        long quota = user.getQuota() == null ? 0L : user.getQuota();
        long delta = size - (existing != null && TYPE_FILE.equals(existing.getType()) ? existing.getSize() : 0);
        if (quota > 0 && usedSpace + delta > quota) {
            throw new WebDavException(507, "Insufficient Storage");
        }
        String parentPathName = resolveNamePath(parent, userId);
        String filePathName = FilePathUtil.buildPathName(parentPathName, name);
        Path physicalPath = FilePathUtil.resolvePhysicalPath(space, user.getUsername(), filePathName);
        try {
            Files.createDirectories(physicalPath.getParent());
            Files.copy(request.getInputStream(), physicalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "文件保存失败", e);
        }
        String hash;
        try {
            hash = FileHashUtil.identityHash(physicalPath);
        } catch (IOException e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "文件 hash 计算失败", e);
        }
        if (existing != null) {
            deleteExistingForOverwrite(existing, user);
        }
        FileNode node = buildFileNode(userId, parent.getId(), name, size, hash, space.getId(),
                probeContentType(name));
        setNodePath(node, parent);
        fileMapper.insert(node);
        user.setUsedSpace(usedSpace + delta);
        userMapper.updateById(user);
    }

    /**
     * 创建文件夹。
     *
     * @param userId   用户 ID
     * @param parentId 父节点 ID
     * @param name     文件夹名称
     */
    public void createFolder(String userId, String parentId, String name) {
        FileNode folder = buildFolderNode(userId, parentId, name);
        FileNode parent = pathResolver.resolveNode(userId, parentId.equals(FileNodeConstants.ROOT_ID) ? "" : null);
        setNodePath(folder, parent);
        fileMapper.insert(folder);
    }

    /**
     * 物理删除节点（递归）。
     *
     * @param userId 用户 ID
     * @param node   要删除的节点
     */
    public void delete(String userId, FileNode node) {
        User user = requireUser(userId);
        deleteNodeRecursively(node, user);
    }

    /**
     * 移动节点到目标父节点。
     *
     * @param userId       用户 ID
     * @param source       源节点
     * @param targetParent 目标父节点
     * @param targetName   目标名称
     */
    public void move(String userId, FileNode source, FileNode targetParent, String targetName) {
        if (TYPE_FOLDER.equals(source.getType())) {
            validateNotMoveToSelfSubtree(source, targetParent.getId(), userId);
        }
        if (TYPE_FILE.equals(source.getType())) {
            StorageSpace space = requireSpace(source.getStorageSpaceId());
            User user = requireUser(userId);
            String oldNamePath = resolveNamePath(source, userId);
            String newNamePath = FilePathUtil.buildPathName(resolveNamePath(targetParent, userId), targetName);
            movePhysical(space, user.getUsername(), oldNamePath, newNamePath);
        } else {
            StorageSpace space = requireSpace(source.getStorageSpaceId());
            User user = requireUser(userId);
            String oldNamePath = resolveNamePath(source, userId);
            String newNamePath = FilePathUtil.buildPathName(resolveNamePath(targetParent, userId), targetName);
            Path oldPath = FilePathUtil.resolvePhysicalPath(space, user.getUsername(), oldNamePath);
            if (Files.exists(oldPath)) {
                movePhysical(space, user.getUsername(), oldNamePath, newNamePath);
            }
        }
        source.setParentId(targetParent.getId());
        source.setName(targetName);
        setNodePath(source, targetParent);
        fileMapper.updateById(source);
    }

    /**
     * 复制节点到目标父节点。
     *
     * @param userId       用户 ID
     * @param source       源节点
     * @param targetParent 目标父节点
     * @param targetName   目标名称
     */
    public void copy(String userId, FileNode source, FileNode targetParent, String targetName) {
        if (TYPE_FOLDER.equals(source.getType())) {
            FileNode copied = buildFolderNode(userId, targetParent.getId(), targetName);
            setNodePath(copied, targetParent);
            fileMapper.insert(copied);
            List<FileNode> children = fileMapper.selectByParentId(userId, source.getId());
            for (FileNode child : children) {
                copy(userId, child, copied, child.getName());
            }
        } else {
            User user = requireUser(userId);
            StorageSpace space = requireSpace(source.getStorageSpaceId());
            String sourceNamePath = resolveNamePath(source, userId);
            String targetNamePath = FilePathUtil.buildPathName(resolveNamePath(targetParent, userId), targetName);
            Path sourcePath = FilePathUtil.resolvePhysicalPath(space, user.getUsername(), sourceNamePath);
            Path targetPath = FilePathUtil.resolvePhysicalPath(space, user.getUsername(), targetNamePath);
            long size = source.getSize() == null ? 0 : source.getSize();
            long usedSpace = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
            long quota = user.getQuota() == null ? 0L : user.getQuota();
            if (quota > 0 && usedSpace + size > quota) {
                throw new WebDavException(507, "Insufficient Storage");
            }
            try {
                Files.createDirectories(targetPath.getParent());
                Files.copy(sourcePath, targetPath);
            } catch (IOException e) {
                throw new SystemException(ResultCode.SYSTEM_ERROR, "复制文件失败", e);
            }
            FileNode copied = buildFileNode(userId, targetParent.getId(), targetName, size,
                    source.getHash(), space.getId(), source.getMimeType());
            copied.setLastModified(source.getLastModified());
            setNodePath(copied, targetParent);
            fileMapper.insert(copied);
            user.setUsedSpace(usedSpace + size);
            userMapper.updateById(user);
        }
    }

    private void deleteExistingForOverwrite(FileNode existing, User user) {
        if (existing == null) {
            return;
        }
        if (TYPE_FOLDER.equals(existing.getType())) {
            deleteNodeRecursively(existing, user);
        } else {
            deleteSingleFile(existing, user);
        }
    }

    private void deleteNodeRecursively(FileNode node, User user) {
        List<FileNode> subtree = collectSubtree(node, user.getId());
        for (FileNode n : subtree) {
            if (TYPE_FILE.equals(n.getType())) {
                deleteSingleFile(n, user);
            }
        }
        List<String> ids = subtree.stream().map(FileNode::getId).toList();
        fileMapper.physicalDeleteByIds(ids);
    }

    private void deleteSingleFile(FileNode node, User user) {
        StorageSpace space = storageSpaceMapper.selectById(node.getStorageSpaceId());
        if (space == null) {
            return;
        }
        String namePath = resolveNamePath(node, user.getId());
        Path physicalPath = FilePathUtil.resolvePhysicalPath(space, user.getUsername(), namePath);
        try {
            Files.deleteIfExists(physicalPath);
        } catch (IOException e) {
            log.warn("删除物理文件失败: {}", physicalPath, e);
        }
        long size = node.getSize() == null ? 0 : node.getSize();
        user.setUsedSpace(Math.max(0, user.getUsedSpace() - size));
    }

    private void movePhysical(StorageSpace space, String username, String oldNamePath, String newNamePath) {
        Path oldPath = FilePathUtil.resolvePhysicalPath(space, username, oldNamePath);
        Path newPath = FilePathUtil.resolvePhysicalPath(space, username, newNamePath);
        try {
            Files.createDirectories(newPath.getParent());
            Files.move(oldPath, newPath);
        } catch (IOException e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "移动文件失败", e);
        }
    }

    private void validateNotMoveToSelfSubtree(FileNode source, String targetParentId, String userId) {
        if (source.getId().equals(targetParentId)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能移动到自身内部");
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
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能移动到自身子目录");
        }
    }

    private List<FileNode> collectSubtree(FileNode node, String userId) {
        List<FileNode> nodes = new ArrayList<>();
        nodes.add(node);
        if (TYPE_FOLDER.equals(node.getType())) {
            List<FileNode> descendants = fileMapper.selectByIdPathPrefix(userId, node.getPath(), node.getId());
            for (FileNode descendant : descendants) {
                if (!descendant.getId().equals(node.getId())) {
                    nodes.add(descendant);
                }
            }
        }
        return nodes;
    }

    private String resolveNamePath(FileNode node, String userId) {
        if (FileNodeConstants.ROOT_ID.equals(node.getId())) {
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

    private User requireUser(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private StorageSpace requireSpace(String spaceId) {
        StorageSpace space = storageSpaceMapper.selectById(spaceId);
        if (space == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }
        return space;
    }

    private FileNode buildFileNode(String userId, String parentId, String name, long size,
                                   String hash, String storageSpaceId, String mimeType) {
        FileNode node = new FileNode();
        node.setId(IdUtil.nextId());
        node.setUserId(userId);
        node.setParentId(parentId);
        node.setName(name);
        node.setType(TYPE_FILE);
        node.setSize(size);
        node.setHash(hash);
        node.setStorageSpaceId(storageSpaceId);
        node.setSourceType(FileNodeConstants.SOURCE_LOCAL);
        node.setMimeType(mimeType);
        node.setStatus(1);
        node.setLastModified(System.currentTimeMillis());
        return node;
    }

    private FileNode buildFolderNode(String userId, String parentId, String name) {
        FileNode folder = new FileNode();
        folder.setId(IdUtil.nextId());
        folder.setUserId(userId);
        folder.setParentId(parentId);
        folder.setName(name);
        folder.setType(TYPE_FOLDER);
        folder.setSize(0L);
        folder.setSourceType(FileNodeConstants.SOURCE_LOCAL);
        folder.setStatus(1);
        folder.setLastModified(System.currentTimeMillis());
        return folder;
    }

    private void setNodePath(FileNode node, FileNode parent) {
        if (parent == null || FileNodeConstants.ROOT_ID.equals(parent.getId())) {
            node.setPath(FileNodeConstants.ROOT_ID);
        } else {
            node.setPath(FilePathUtil.fullIdPath(parent));
        }
    }

    private String probeContentType(String name) {
        try {
            String probe = Files.probeContentType(Path.of(name));
            if (probe != null) {
                return probe;
            }
        } catch (IOException e) {
            log.debug("探测 MIME 类型失败: {}", name, e);
        }
        return "application/octet-stream";
    }
}
