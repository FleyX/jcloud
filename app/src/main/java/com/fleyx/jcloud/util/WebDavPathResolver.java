package com.fleyx.jcloud.util;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.po.FileNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * WebDAV 路径解析器。
 * <p>
 * 将 WebDAV 请求路径解析为文件节点或父节点+名称。
 */
@Component
public class WebDavPathResolver {

    private static final String TYPE_FOLDER = "folder";

    private final FileMapper fileMapper;

    public WebDavPathResolver(FileMapper fileMapper) {
        this.fileMapper = fileMapper;
    }

    /**
     * 解析路径对应的文件节点。
     *
     * @param userId 用户 ID
     * @param path   WebDAV 相对路径
     * @return 文件节点；路径为空返回虚拟根节点；不存在返回 null
     */
    public FileNode resolveNode(String userId, String path) {
        if (!StringUtils.hasText(path)) {
            return buildRootNode(userId);
        }
        String[] parts = path.split("/");
        FileNode current = buildRootNode(userId);
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            FileNode child = findChild(userId, current.getId(), part);
            if (child == null) {
                return null;
            }
            current = child;
        }
        return current;
    }

    /**
     * 解析路径的父节点和最终名称。
     *
     * @param userId 用户 ID
     * @param path   WebDAV 相对路径
     * @return 父节点与名称；父目录不存在时 parent 为 null
     */
    public ParentName resolveParentAndName(String userId, String path) {
        if (!StringUtils.hasText(path)) {
            return new ParentName(buildRootNode(userId), "");
        }
        int idx = path.lastIndexOf('/');
        if (idx < 0) {
            return new ParentName(buildRootNode(userId), path);
        }
        String parentPath = path.substring(0, idx);
        String name = path.substring(idx + 1);
        FileNode parent = resolveNode(userId, parentPath);
        return new ParentName(parent, name);
    }

    /**
     * 从请求 URI 中提取 WebDAV 相对路径。
     *
     * @param uri      请求 URI
     * @param userCode 用户编码
     * @return 解码后的相对路径
     */
    public String extractDavPath(String uri, String userCode) {
        String prefix = "/dav/" + userCode;
        String remaining = uri.substring(prefix.length());
        if (remaining.startsWith("/")) {
            remaining = remaining.substring(1);
        }
        return decodePath(remaining);
    }

    /**
     * 从 Destination 头中提取完整路径（保留前导 /）。
     *
     * @param destination Destination 头值
     * @return 解码后的完整路径，格式如 /dav/{userCode}/path
     */
    public String extractDestinationPath(String destination) {
        try {
            java.net.URL url = new java.net.URL(destination);
            return decodePathKeepLeadingSlash(url.getPath());
        } catch (java.net.MalformedURLException e) {
            return decodePathKeepLeadingSlash(destination);
        }
    }

    private String decodePath(String path) {
        String decoded = decodePathKeepLeadingSlash(path);
        if (decoded.startsWith("/")) {
            decoded = decoded.substring(1);
        }
        if (decoded.endsWith("/")) {
            decoded = decoded.substring(0, decoded.length() - 1);
        }
        return decoded;
    }

    private String decodePathKeepLeadingSlash(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        String decoded = URLDecoder.decode(path, StandardCharsets.UTF_8);
        if (decoded.endsWith("/")) {
            decoded = decoded.substring(0, decoded.length() - 1);
        }
        return decoded;
    }

    private FileNode buildRootNode(String userId) {
        FileNode root = new FileNode();
        root.setId(FileNodeConstants.ROOT_ID);
        root.setUserId(userId);
        root.setParentId(FileNodeConstants.ROOT_ID);
        root.setName("");
        root.setType(TYPE_FOLDER);
        root.setPath(FileNodeConstants.ROOT_ID);
        return root;
    }

    private FileNode findChild(String userId, String parentId, String name) {
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileNode::getUserId, userId);
        wrapper.eq(FileNode::getParentId, parentId);
        wrapper.eq(FileNode::getName, name);
        return fileMapper.selectOne(wrapper);
    }

    /**
     * 父节点与名称组合。
     *
     * @param parent 父节点
     * @param name   名称
     */
    public record ParentName(FileNode parent, String name) {
    }
}
