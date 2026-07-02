package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.bo.RemoteFileEntry;

import java.io.InputStream;
import java.util.List;

/**
 * 远程存储协议适配器策略接口。
 * <p>
 * 所有远程操作以远端路径（rootPath 之后的绝对路径）为参数。
 */
public interface RemoteProtocolAdapter {

    /**
     * 列出指定远程目录下的直接子项。
     *
     * @param remotePath 远程目录路径
     * @return 子项元数据列表
     */
    List<RemoteFileEntry> listChildren(String remotePath);

    /**
     * 下载远程文件。
     *
     * @param remotePath 远程文件路径
     * @return 文件输入流，调用方负责关闭
     */
    InputStream download(String remotePath);

    /**
     * 上传文件到远程路径。
     *
     * @param remotePath 远程文件路径
     * @param inputStream 文件输入流
     * @param size 文件大小（字节），-1 表示未知
     * @param mimeType MIME 类型，可为空
     */
    void upload(String remotePath, InputStream inputStream, long size, String mimeType);

    /**
     * 删除远程文件或文件夹。
     *
     * @param remotePath 远程路径
     */
    void delete(String remotePath);

    /**
     * 移动/重命名远程文件或文件夹。
     *
     * @param oldRemotePath 原远程路径
     * @param newRemotePath 新远程路径
     */
    void move(String oldRemotePath, String newRemotePath);

    /**
     * 创建远程文件夹。
     *
     * @param remotePath 远程文件夹路径
     */
    void createFolder(String remotePath);

    /**
     * 判断远程路径是否存在。
     *
     * @param remotePath 远程路径
     * @return 是否存在
     */
    boolean exists(String remotePath);
}
