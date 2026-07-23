package com.fleyx.jcloud.service.impl;

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
import com.fleyx.jcloud.service.support.FileNodeSupport;
import com.fleyx.jcloud.service.support.FilePathSupport;
import com.fleyx.jcloud.service.support.UserSpaceSupport;
import com.fleyx.jcloud.util.FileConflictHelper;
import com.fleyx.jcloud.util.FileHashUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * WebDAV 文件操作辅助类。
 * <p>
 * 封装上传、删除、移动、复制、创建文件夹等写操作，必须在调用前获取用户写锁。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebDavFileOperationHelper {

    private final FileMapper fileMapper;
    private final UserMapper userMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final UserSpaceSupport userSpaceSupport;
    private final FileNodeSupport fileNodeSupport;
    private final FilePathSupport filePathSupport;

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
        User user = userSpaceSupport.requireUser(userId);
        StorageSpace space = userSpaceSupport.requireSpace(user.getStorageSpaceId());
        long usedSpace = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        long quota = user.getQuota() == null ? 0L : user.getQuota();
        long delta = size - (existing != null && FileNodeConstants.TYPE_FILE.equals(existing.getType()) ? existing.getSize() : 0);
        if (quota > 0 && usedSpace + delta > quota) {
            throw new WebDavException(507, "Insufficient Storage");
        }
        String parentPathName = filePathSupport.resolveNamePath(parent, userId);
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
        FileNode node = fileNodeSupport.buildFileNode(userId, parent.getId(), name, size, hash, space.getId(),
                probeContentType(name));
        node.setLastModified(System.currentTimeMillis());
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
        FileNode folder = fileNodeSupport.buildFolderNode(userId, parentId, name);
        folder.setLastModified(System.currentTimeMillis());
        FileNode parent = FileNodeConstants.ROOT_ID.equals(parentId) ? null : fileMapper.selectById(parentId);
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
        User user = userSpaceSupport.requireUser(userId);
        deleteNodeRecursively(node, user);
        userMapper.updateById(user);
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
        if (FileNodeConstants.TYPE_FOLDER.equals(source.getType())) {
            validateNotMoveToSelfSubtree(source, targetParent.getId(), userId);
        }
        StorageSpace space = userSpaceSupport.requireSpace(source.getStorageSpaceId());
        User user = userSpaceSupport.requireUser(userId);
        String oldNamePath = filePathSupport.resolveNamePath(source, userId);
        String newNamePath = FilePathUtil.buildPathName(filePathSupport.resolveNamePath(targetParent, userId), targetName);
        if (FileNodeConstants.TYPE_FILE.equals(source.getType())
                || Files.exists(FilePathUtil.resolvePhysicalPath(space, user.getUsername(), oldNamePath))) {
            movePhysical(space, user.getUsername(), oldNamePath, newNamePath);
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
        if (FileNodeConstants.TYPE_FOLDER.equals(source.getType())) {
            FileNode copied = fileNodeSupport.buildFolderNode(userId, targetParent.getId(), targetName);
            copied.setLastModified(System.currentTimeMillis());
            setNodePath(copied, targetParent);
            fileMapper.insert(copied);
            List<FileNode> children = fileMapper.selectByParentId(userId, source.getId());
            for (FileNode child : children) {
                copy(userId, child, copied, child.getName());
            }
        } else {
            User user = userSpaceSupport.requireUser(userId);
            StorageSpace space = userSpaceSupport.requireSpace(source.getStorageSpaceId());
            String sourceNamePath = filePathSupport.resolveNamePath(source, userId);
            String targetNamePath = FilePathUtil.buildPathName(filePathSupport.resolveNamePath(targetParent, userId), targetName);
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
            FileNode copied = fileNodeSupport.buildFileNode(userId, targetParent.getId(), targetName, size,
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
        if (FileNodeConstants.TYPE_FOLDER.equals(existing.getType())) {
            deleteNodeRecursively(existing, user);
        } else {
            deleteSingleFile(existing, user);
        }
    }

    private void deleteNodeRecursively(FileNode node, User user) {
        List<FileNode> subtree = collectSubtree(node, user.getId());
        for (FileNode n : subtree) {
            if (FileNodeConstants.TYPE_FILE.equals(n.getType())) {
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
        String namePath = filePathSupport.resolveNamePath(node, user.getId());
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
        if (FileNodeConstants.TYPE_FOLDER.equals(node.getType())) {
            List<FileNode> descendants = fileMapper.selectByIdPathPrefix(userId, node.getPath(), node.getId());
            for (FileNode descendant : descendants) {
                if (!descendant.getId().equals(node.getId())) {
                    nodes.add(descendant);
                }
            }
        }
        return nodes;
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
