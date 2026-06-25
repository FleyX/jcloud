package com.fleyx.jcloud.util;

import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;

import java.nio.file.Path;

/**
 * 文件物理路径工具。
 * <p>
 * pathName 约定：
 * - 文件节点：pathName 为父目录的完整虚拟路径（不含自身文件名）。
 * - 文件夹节点：pathName 为包含自身的完整虚拟路径。
 */
public final class FilePathUtil {

    private FilePathUtil() {
    }

    /**
     * 解析文件节点在存储空间中的绝对物理路径。
     * <p>
     * 路径公式：storageSpace.path / userId / files / pathName / name
     * 其中 pathName 以 '/' 开头表示根目录，根目录 pathName 为空字符串。
     *
     * @param node  文件节点
     * @param space 存储空间
     * @return 物理路径
     */
    public static Path resolvePhysicalPath(FileNode node, StorageSpace space) {
        return resolvePhysicalPath(space, node.getUserId(), node.getPathName(), node.getName());
    }

    /**
     * 解析指定 pathName 与名称对应的物理路径。
     *
     * @param space    存储空间
     * @param userId   用户 ID
     * @param pathName 父目录 pathName（文件）或自身完整 pathName（文件夹）
     * @param name     节点名称
     * @return 物理路径
     */
    public static Path resolvePhysicalPath(StorageSpace space, Long userId,
                                           String pathName, String name) {
        String userCode = userId.toString();
        Path base = Path.of(space.getPath(), userCode, "files");
        if (pathName == null || pathName.isBlank() || "/".equals(pathName)) {
            return base.resolve(name);
        }
        String relative = pathName.startsWith("/") ? pathName.substring(1) : pathName;
        return base.resolve(relative).resolve(name);
    }

    /**
     * 解析文件夹节点的绝对物理路径（不含文件名）。
     *
     * @param node  文件夹节点
     * @param space 存储空间
     * @return 文件夹物理路径
     */
    public static Path resolveFolderPhysicalPath(FileNode node, StorageSpace space) {
        String userCode = node.getUserId().toString();
        Path base = Path.of(space.getPath(), userCode, "files");
        String pathName = node.getPathName();
        if (pathName == null || pathName.isBlank() || "/".equals(pathName)) {
            return base;
        }
        String relative = pathName.startsWith("/") ? pathName.substring(1) : pathName;
        return base.resolve(relative);
    }

    /**
     * 根据父节点 pathName 和名称构建子节点 pathName。
     * <p>
     * 适用于文件夹节点；文件节点的 pathName 应直接复用父 pathName。
     *
     * @param parentPathName 父节点 pathName
     * @param name           子节点名称
     * @return 子节点 pathName
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
}
