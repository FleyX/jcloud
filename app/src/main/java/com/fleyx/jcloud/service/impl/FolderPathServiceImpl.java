package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.service.FolderPathService;
import com.fleyx.jcloud.util.FileNodeUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 文件夹路径解析服务实现。
 */
@Service
@RequiredArgsConstructor
public class FolderPathServiceImpl implements FolderPathService {

    private static final String TYPE_FOLDER = "folder";
    private static final String TYPE_FILE = "file";
    private static final int MAX_PATH_DEPTH = 100;

    private final FileMapper fileMapper;
    private final UserMapper userMapper;

    @Override
    public String resolveOrCreateFolderPath(String userId, String parentId, String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            return FileNodeUtil.normalizeParentId(parentId);
        }

        String normalizedPath = normalizeRelativePath(relativePath);
        String dirPath = extractDirectoryPath(normalizedPath);
        if (!StringUtils.hasText(dirPath)) {
            return FileNodeUtil.normalizeParentId(parentId);
        }

        List<String> segments = splitPath(dirPath);
        if (segments.isEmpty()) {
            return FileNodeUtil.normalizeParentId(parentId);
        }
        if (segments.size() > MAX_PATH_DEPTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "文件夹层级超过最大限制");
        }

        String currentParentId = FileNodeUtil.normalizeParentId(parentId);
        validateTargetParent(currentParentId, userId);

        User user = requireUser(userId);
        String storageSpaceId = user.getStorageSpaceId();

        for (String segment : segments) {
            validateSegment(segment);
            FileNode existing = findChildFolder(userId, currentParentId, segment);
            if (existing != null) {
                currentParentId = existing.getId();
                continue;
            }
            FileNode conflictFile = findChildFile(userId, currentParentId, segment);
            if (conflictFile != null) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR,
                        "路径中存在同名文件，无法创建文件夹: " + segment);
            }
            FileNode folder = createFolderNode(userId, currentParentId, segment, storageSpaceId);
            fileMapper.insert(folder);
            currentParentId = folder.getId();
        }

        return currentParentId;
    }

    private String normalizeRelativePath(String relativePath) {
        String trimmed = relativePath.trim();
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "相对路径不能以根分隔符开头");
        }

        List<String> segments = Arrays.stream(trimmed.replace("\\", "/").split("/"))
                .filter(StringUtils::hasText)
                .toList();

        List<String> normalized = new ArrayList<>();
        for (String segment : segments) {
            if (".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (normalized.isEmpty()) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "相对路径包含非法的 '..' 段");
                }
                normalized.remove(normalized.size() - 1);
                continue;
            }
            normalized.add(segment);
        }

        return String.join("/", normalized);
    }

    private String extractDirectoryPath(String normalizedPath) {
        if (!normalizedPath.contains("/")) {
            return "";
        }
        int lastSlashIndex = normalizedPath.lastIndexOf('/');
        return normalizedPath.substring(0, lastSlashIndex);
    }

    private List<String> splitPath(String dirPath) {
        return Arrays.stream(dirPath.split("/"))
                .filter(StringUtils::hasText)
                .toList();
    }

    private void validateSegment(String segment) {
        if (!StringUtils.hasText(segment) || segment.contains("/") || segment.contains("\\")) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "文件夹名称包含非法字符");
        }
    }

    private void validateTargetParent(String parentId, String userId) {
        if (FileNodeConstants.ROOT_ID.equals(parentId)) {
            return;
        }
        FileNode parent = fileMapper.selectById(parentId);
        if (parent == null || !userId.equals(parent.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "父目录不存在");
        }
        if (!TYPE_FOLDER.equals(parent.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "目标父节点不是文件夹");
        }
    }

    private FileNode findChildFolder(String userId, String parentId, String name) {
        return fileMapper.selectList(new LambdaQueryWrapper<FileNode>()
                        .eq(FileNode::getUserId, userId)
                        .eq(FileNode::getParentId, parentId)
                        .eq(FileNode::getName, name)
                        .eq(FileNode::getType, TYPE_FOLDER))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private FileNode findChildFile(String userId, String parentId, String name) {
        return fileMapper.selectList(new LambdaQueryWrapper<FileNode>()
                        .eq(FileNode::getUserId, userId)
                        .eq(FileNode::getParentId, parentId)
                        .eq(FileNode::getName, name)
                        .eq(FileNode::getType, TYPE_FILE))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private FileNode createFolderNode(String userId, String parentId, String name, String storageSpaceId) {
        FileNode folder = new FileNode();
        folder.setUserId(userId);
        folder.setParentId(parentId);
        folder.setName(name);
        folder.setType(TYPE_FOLDER);
        folder.setSize(0L);
        folder.setStorageSpaceId(storageSpaceId);
        folder.setSourceType(FileNodeConstants.SOURCE_LOCAL);
        folder.setStatus(1);
        FileNode parent = FileNodeConstants.ROOT_ID.equals(parentId) ? null : fileMapper.selectById(parentId);
        setNodePath(folder, parent);
        return folder;
    }

    private void setNodePath(FileNode node, FileNode parent) {
        if (parent == null || FileNodeConstants.ROOT_ID.equals(parent.getId())) {
            node.setPath(FileNodeConstants.ROOT_ID);
        } else {
            node.setPath(FilePathUtil.fullIdPath(parent));
        }
    }

    private User requireUser(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }
}
