package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.dto.FileInstantUploadDto;
import com.fleyx.jcloud.model.dto.FileUploadPreCheckDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.UploadPreCheckVo;
import org.springframework.web.multipart.MultipartFile;

/**
 * 远程文件上传/下载服务。
 */
public interface RemoteFileService {

    /**
     * 上传文件到远程目录。
     *
     * @param file       上传文件
     * @param parentNode 远程父目录节点
     * @param userId     用户 ID
     * @param finalName  最终文件名（已处理冲突）
     * @return 文件节点视图
     */
    FileNodeVo upload(MultipartFile file, FileNode parentNode, String userId, String finalName);

    /**
     * 下载远程文件。
     *
     * @param node   远程文件节点
     * @param userId 用户 ID
     * @return 文件下载结果
     */
    FileDownloadResult download(FileNode node, String userId);

    /**
     * 远程上传前预检。
     *
     * @param dto        预检参数
     * @param parentNode 远程父目录节点
     * @param userId     用户 ID
     * @return 预检结果
     */
    UploadPreCheckVo preCheckUpload(FileUploadPreCheckDto dto, FileNode parentNode, String userId);

    /**
     * 远程目录不支持秒传。
     *
     * @param dto        秒传参数
     * @param parentNode 远程父目录节点
     * @param userId     用户 ID
     * @param finalName  最终文件名
     * @return 始终抛出业务异常
     */
    FileNodeVo instantUpload(FileInstantUploadDto dto, FileNode parentNode, String userId, String finalName);

    /**
     * 在远程目录下创建文件夹。
     *
     * @param parentNode 远程父目录节点
     * @param name       文件夹名称
     * @param userId     用户 ID
     * @return 文件夹节点视图
     */
    FileNodeVo createFolder(FileNode parentNode, String name, String userId);

    /**
     * 上传字节内容到远程目录（内部写回通道，如 NFO/媒体图片落盘）。
     * 已存在同名文件节点时覆盖。
     *
     * @param parentNode 远程父目录节点
     * @param userId     用户 ID
     * @param finalName  最终文件名
     * @param content    文件内容
     * @param mimeType   MIME 类型，可为空
     * @return 文件节点
     */
    FileNode uploadContent(FileNode parentNode, String userId, String finalName, byte[] content, String mimeType);
}
