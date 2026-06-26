package com.fleyx.jcloud.util;

import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;

import java.nio.file.Path;

/**
 * 文件物理路径工具。
 * <p>
 * pathName 约定：对文件和文件夹统一表示节点自身的完整虚拟路径，均以 '/' 开头。
 * <ul>
 *     <li>文件：/docs/report.pdf</li>
 *     <li>文件夹：/docs 或 /docs/sub</li>
 * </ul>
 * 物理路径公式：storageSpace.path / userId / files / pathName（去掉前导 '/'）
 */
public final class FilePathUtil {

    private FilePathUtil() {
    }

    /**
     * 解析文件节点在存储空间中的绝对物理路径。
     *
     * @param node  文件节点
     * @param space 存储空间
     * @return 物理路径
     */
    public static Path resolvePhysicalPath(FileNode node, StorageSpace space) {
        return resolvePhysicalPath(space, node.getUserId(), node.getPathName());
    }

    /**
     * 解析指定完整虚拟路径对应的绝对物理路径。
     *
     * @param space    存储空间
     * @param userId   用户 ID
     * @param pathName 节点自身完整虚拟路径
     * @return 物理路径
     */
    public static Path resolvePhysicalPath(StorageSpace space, Long userId, String pathName) {
        Path base = Path.of(space.getPath(), userId.toString(), "files");
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
     * @param userId         用户 ID
     * @param parentPathName 父目录完整虚拟路径
     * @param name           节点名称
     * @return 物理路径
     */
    public static Path resolvePhysicalPath(StorageSpace space, Long userId,
                                           String parentPathName, String name) {
        return resolvePhysicalPath(space, userId, buildPathName(parentPathName, name));
    }

    /**
     * 解析文件夹节点的绝对物理路径（与文件节点等价，pathName 包含自身）。
     *
     * @param node  文件夹节点
     * @param space 存储空间
     * @return 文件夹物理路径
     */
    public static Path resolveFolderPhysicalPath(FileNode node, StorageSpace space) {
        return resolvePhysicalPath(node, space);
    }

    /**
     * 根据父节点 pathName 和名称构建子节点完整 pathName。
     *
     * @param parentPathName 父节点完整虚拟路径
     * @param name           子节点名称
     * @return 子节点完整虚拟路径
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
     * 获取指定完整虚拟路径的父目录路径。
     *
     * @param pathName 节点自身完整虚拟路径
     * @return 父目录完整虚拟路径，根目录返回 "/"
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
     * 获取指定完整虚拟路径的节点名称。
     *
     * @param pathName 节点自身完整虚拟路径
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
     * @param pathName 节点自身完整虚拟路径
     * @return 相对路径
     */
    public static String stripLeadingSlash(String pathName) {
        if (pathName == null || pathName.isBlank() || "/".equals(pathName)) {
            return "";
        }
        return pathName.startsWith("/") ? pathName.substring(1) : pathName;
    }
}
