package com.fleyx.jcloud.model.dto;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import lombok.Data;

/**
 * 初始化分片上传请求参数。
 */
@Data
public class ChunkedUploadInitDto {

    /**
     * 文件名。
     */
    private String fileName;

    /**
     * 文件大小（字节）。
     */
    private Long size;

    /**
     * 目标父节点 ID，默认为根目录 {@link FileNodeConstants#ROOT_ID}。
     */
    private String parentId;

    /**
     * 相对路径（用于文件夹上传），包含文件名，例如 {@code project/src/main.java}。
     * <p>
     * 目录部分相对于 {@code parentId} 表示文件夹层级，后端会自动创建缺失的文件夹。
     */
    private String relativePath;
}
