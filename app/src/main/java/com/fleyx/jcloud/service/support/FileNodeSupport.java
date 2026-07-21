package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.util.FileConflictHelper;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 文件节点共享支撑组件。
 * <p>
 * 收敛各文件服务中重复出现的节点归属查询、同名子节点查询、节点构建与路径设置逻辑。
 */
@Component
@RequiredArgsConstructor
public class FileNodeSupport {

    private static final String TYPE_FILE = "file";
    private static final String TYPE_FOLDER = "folder";

    private final FileMapper fileMapper;
    private final UserMapper userMapper;

    /**
     * 按 ID 查询节点并校验归属。
     *
     * @param nodeId 节点 ID
     * @param userId 用户 ID
     * @return 节点
     */
    public FileNode getOwnedNode(String nodeId, String userId) {
        if (nodeId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "节点 ID 不能为空");
        }
        FileNode node = fileMapper.selectById(nodeId);
        if (node == null || !userId.equals(node.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "节点不存在");
        }
        return node;
    }

    /**
     * 查询指定父目录下的同名子节点。
     *
     * @param userId   用户 ID
     * @param parentId 父节点 ID
     * @param name     子节点名称
     * @return 同名子节点，不存在返回 {@code null}
     */
    public FileNode findExistingChild(String userId, String parentId, String name) {
        return FileConflictHelper.findSameName(fileMapper, userId, parentId, name);
    }

    /**
     * 按名称路径逐级查找文件夹节点。
     *
     * @param userId   用户 ID
     * @param pathName 文件夹完整名称路径
     * @return 文件夹节点，任一级不存在返回 {@code null}
     */
    public FileNode findFolderByPathName(String userId, String pathName) {
        if (pathName == null || "/".equals(pathName) || pathName.isBlank()) {
            return null;
        }
        String[] parts = FilePathUtil.stripLeadingSlash(pathName).split("/");
        String parentId = FileNodeConstants.ROOT_ID;
        FileNode current = null;
        for (String part : parts) {
            current = findFolderByName(userId, parentId, part);
            if (current == null) {
                return null;
            }
            parentId = current.getId();
        }
        return current;
    }

    /**
     * 查询指定父目录下的同名文件夹节点。
     *
     * @param userId   用户 ID
     * @param parentId 父节点 ID
     * @param name     文件夹名称
     * @return 文件夹节点，不存在返回 {@code null}
     */
    public FileNode findFolderByName(String userId, String parentId, String name) {
        return fileMapper.selectOne(
                new LambdaQueryWrapper<FileNode>()
                        .eq(FileNode::getUserId, userId)
                        .eq(FileNode::getParentId, parentId)
                        .eq(FileNode::getName, name)
                        .eq(FileNode::getType, TYPE_FOLDER));
    }

    /**
     * 构建文件夹节点，存储空间按用户绑定关系内部解析。
     *
     * @param userId   用户 ID
     * @param parentId 父节点 ID
     * @param name     文件夹名称
     * @return 文件夹节点（未持久化）
     */
    public FileNode buildFolderNode(String userId, String parentId, String name) {
        FileNode folder = new FileNode();
        folder.setUserId(userId);
        folder.setParentId(parentId);
        folder.setName(name);
        folder.setType(TYPE_FOLDER);
        folder.setSize(0L);
        folder.setStorageSpaceId(resolveStorageSpaceId(userId));
        folder.setSourceType(FileNodeConstants.SOURCE_LOCAL);
        folder.setStatus(1);
        return folder;
    }

    /**
     * 构建文件节点。
     *
     * @param userId         用户 ID
     * @param parentId       父节点 ID
     * @param name           文件名称
     * @param size           文件大小
     * @param hash           文件 hash
     * @param storageSpaceId 存储空间 ID
     * @param mimeType       MIME 类型
     * @return 文件节点（未持久化）
     */
    public FileNode buildFileNode(String userId, String parentId, String name, long size,
                                  String hash, String storageSpaceId, String mimeType) {
        FileNode node = new FileNode();
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
        return node;
    }

    /**
     * 根据父节点 ID 设置节点的 ID 路径。
     *
     * @param node     节点
     * @param parentId 父节点 ID
     */
    public void setNodePath(FileNode node, String parentId) {
        if (FileNodeConstants.ROOT_ID.equals(parentId)) {
            node.setPath(FileNodeConstants.ROOT_ID);
        } else {
            FileNode parent = fileMapper.selectById(parentId);
            if (parent == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "父目录不存在");
            }
            node.setPath(FilePathUtil.fullIdPath(parent));
        }
    }

    /**
     * 解析用户绑定的存储空间 ID。
     *
     * @param userId 用户 ID
     * @return 存储空间 ID
     */
    public String resolveStorageSpaceId(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getStorageSpaceId() == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户未绑定存储空间");
        }
        return user.getStorageSpaceId();
    }
}
