package com.fleyx.jcloud.service;

/**
 * 文件夹路径解析服务。
 * <p>
 * 用于文件夹上传场景：根据相对路径自动解析并创建目标文件夹层级，
 * 返回文件最终应存放的父节点 ID。
 */
public interface FolderPathService {

    /**
     * 解析相对路径的目录部分，自动创建缺失的文件夹，返回最终父节点 ID。
     *
     * @param userId       用户 ID
     * @param parentId     起始父节点 ID
     * @param relativePath 相对路径，包含文件名，例如 {@code project/src/main.java}
     * @return 文件应存放的最终父节点 ID
     */
    String resolveOrCreateFolderPath(String userId, String parentId, String relativePath);
}
