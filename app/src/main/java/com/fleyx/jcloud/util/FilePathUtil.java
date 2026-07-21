package com.fleyx.jcloud.util;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.constant.StorageConstant;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 文件物理路径工具。
 * <p>
 * 物理路径公式：storageSpace.path / files / username / namePath（去掉前导 '/'）
 * <p>
 * namePath 约定：对文件和文件夹统一表示节点自身的完整虚拟名称路径，均以 '/' 开头。
 * <ul>
 *     <li>文件：/docs/report.pdf</li>
 *     <li>文件夹：/docs 或 /docs/sub</li>
 * </ul>
 * 由于数据库中只保存 id 路径，本工具提供基于 id 路径 + id→name 缓存的名称路径解析能力。
 */
public final class FilePathUtil {

    private FilePathUtil() {
    }

    /**
     * 路径解析上下文，用于批量内缓存 id→name 映射，避免重复查库。
     *
     * @param space         存储空间
     * @param username      用户名，作为物理路径中的用户目录名
     * @param idToNameCache id 到名称的缓存映射
     */
    public record ResolveContext(StorageSpace space, String username, Map<String, String> idToNameCache) {
    }

    /**
     * 创建路径解析上下文。
     *
     * @param space    存储空间
     * @param username 用户名
     * @return 上下文
     */
    public static ResolveContext contextOf(StorageSpace space, String username) {
        return new ResolveContext(space, username, new HashMap<>());
    }

    /**
     * 创建路径解析上下文，使用指定的 id→name 缓存。
     *
     * @param space    存储空间
     * @param username 用户名
     * @param cache    id 到名称缓存
     * @return 上下文
     */
    public static ResolveContext contextOf(StorageSpace space, String username, Map<String, String> cache) {
        return new ResolveContext(space, username, cache);
    }

    /**
     * 解析文件节点在存储空间中的绝对物理路径。
     *
     * @param node 文件节点
     * @param ctx  解析上下文
     * @return 物理路径
     */
    public static Path resolvePhysicalPath(FileNode node, ResolveContext ctx) {
        return resolvePhysicalPath(ctx.space(), ctx.username(), resolveNamePath(node, ctx));
    }

    /**
     * 解析文件夹节点的绝对物理路径（与文件节点等价，namePath 包含自身）。
     *
     * @param node  文件夹节点
     * @param ctx   解析上下文
     * @return 文件夹物理路径
     */
    public static Path resolveFolderPhysicalPath(FileNode node, ResolveContext ctx) {
        return resolvePhysicalPath(node, ctx);
    }

    /**
     * 根据 id 路径解析节点的完整名称路径。
     * <p>
     * 依赖 {@link ResolveContext#idToNameCache()} 包含所有非根祖先 id 的名称。
     *
     * @param node 文件节点
     * @param ctx  解析上下文
     * @return 完整名称路径
     */
    public static String resolveNamePath(FileNode node, ResolveContext ctx) {
        if (node == null || node.getName() == null) {
            return "/";
        }
        String idPath = node.getPath();
        String parentNamePath;
        if (idPath == null || idPath.isBlank() || FileNodeConstants.ROOT_ID.equals(idPath)) {
            parentNamePath = "/";
        } else {
            String[] ids = idPath.split("\\.");
            StringBuilder builder = new StringBuilder();
            for (String id : ids) {
                if (FileNodeConstants.ROOT_ID.equals(id)) {
                    continue;
                }
                String name = ctx.idToNameCache().get(id);
                if (name == null) {
                    throw new IllegalArgumentException("缺少 id 对应名称: " + id);
                }
                builder.append("/").append(name);
            }
            parentNamePath = builder.isEmpty() ? "/" : builder.toString();
        }
        return buildPathName(parentNamePath, node.getName());
    }

    /**
     * 解析指定完整名称路径对应的绝对物理路径。
     *
     * @param space    存储空间
     * @param username 用户名
     * @param pathName 节点自身完整名称路径
     * @return 物理路径
     */
    public static Path resolvePhysicalPath(StorageSpace space, String username, String pathName) {
        Path base = Path.of(space.getPath(), StorageConstant.FILES_DIR, username);
        String relative = stripLeadingSlash(pathName);
        if (relative.isEmpty()) {
            return base;
        }
        return base.resolve(relative);
    }

    /**
     * 根据父路径和名称解析绝对物理路径。
     *
     * @param space          存储空间
     * @param username       用户名
     * @param parentPathName 父目录完整名称路径
     * @param name           节点名称
     * @return 物理路径
     */
    public static Path resolvePhysicalPath(StorageSpace space, String username,
                                           String parentPathName, String name) {
        return resolvePhysicalPath(space, username, buildPathName(parentPathName, name));
    }

    /**
     * 根据父节点 namePath 和名称构建子节点完整 namePath。
     *
     * @param parentPathName 父节点完整名称路径
     * @param name           子节点名称
     * @return 子节点完整名称路径
     */
    public static String buildPathName(String parentPathName, String name) {
        if (parentPathName == null || parentPathName.isBlank() || "/".equals(parentPathName)) {
            return "/" + name;
        }
        if (parentPathName.endsWith("/")) {
            return parentPathName + name;
        }
        return parentPathName + "/" + name;
    }

    /**
     * 获取指定完整名称路径的父目录路径。
     *
     * @param pathName 节点自身完整名称路径
     * @return 父目录完整名称路径，根目录返回 "/"
     */
    public static String parentOf(String pathName) {
        if (pathName == null || "/".equals(pathName) || pathName.isBlank()) {
            return "/";
        }
        String normalized = pathName.endsWith("/")
                ? pathName.substring(0, pathName.length() - 1)
                : pathName;
        int idx = normalized.lastIndexOf('/');
        if (idx <= 0) {
            return "/";
        }
        return normalized.substring(0, idx);
    }

    /**
     * 获取指定完整名称路径的节点名称。
     *
     * @param pathName 节点自身完整名称路径
     * @return 节点名称
     */
    public static String nameOf(String pathName) {
        if (pathName == null || pathName.isBlank() || "/".equals(pathName)) {
            return "";
        }
        String normalized = pathName.endsWith("/")
                ? pathName.substring(0, pathName.length() - 1)
                : pathName;
        int idx = normalized.lastIndexOf('/');
        return idx < 0 ? normalized : normalized.substring(idx + 1);
    }

    /**
     * 去掉 pathName 的前导 '/'，根目录返回空字符串。
     *
     * @param pathName 节点自身完整名称路径
     * @return 相对路径
     */
    public static String stripLeadingSlash(String pathName) {
        if (pathName == null || pathName.isBlank() || "/".equals(pathName)) {
            return "";
        }
        return pathName.startsWith("/") ? pathName.substring(1) : pathName;
    }

    /**
     * 计算节点的完整 id 路径（包含自身 id）。
     * <p>
     * 约定：完整 id 路径始终以虚拟根节点 ID 开头，使用 "." 分隔。
     *
     * @param node 文件节点
     * @return 完整 id 路径
     */
    public static String fullIdPath(FileNode node) {
        if (node == null || node.getId() == null) {
            return null;
        }
        String path = node.getPath();
        if (path == null || path.isBlank() || FileNodeConstants.ROOT_ID.equals(path)) {
            return FileNodeConstants.ROOT_ID + FileNodeConstants.PATH_SEPARATOR + node.getId();
        }
        return path + FileNodeConstants.PATH_SEPARATOR + node.getId();
    }

    /**
     * 解析回收站记录的物理根目录。
     *
     * @param space    存储空间
     * @param username 用户名
     * @param recordId 回收站记录 ID
     * @return 回收站记录物理根目录
     */
    public static Path resolveTrashRoot(StorageSpace space, String username, String recordId) {
        return Path.of(space.getPath(), StorageConstant.TRASH_DIR, username, recordId);
    }

    /**
     * 从节点集合中提取所有非根祖先 id。
     *
     * @param nodes 文件节点集合
     * @return 祖先 id 集合
     */
    public static Set<String> extractAncestorIds(Collection<FileNode> nodes) {
        Set<String> ids = new HashSet<>();
        for (FileNode node : nodes) {
            String path = node.getPath();
            if (path == null || path.isBlank()) {
                continue;
            }
            for (String id : path.split("\\.")) {
                if (!FileNodeConstants.ROOT_ID.equals(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /**
     * 使用节点自身 id 与 name 构建 id→name 缓存。
     *
     * @param nodes 文件节点集合
     * @return 缓存映射
     */
    public static Map<String, String> buildNameCache(Collection<FileNode> nodes) {
        Map<String, String> cache = new HashMap<>();
        if (nodes == null) {
            return cache;
        }
        for (FileNode node : nodes) {
            if (node.getId() != null && node.getName() != null) {
                cache.put(node.getId(), node.getName());
            }
        }
        return cache;
    }
}
