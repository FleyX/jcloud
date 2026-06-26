package com.fleyx.jcloud.model.dto;

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
     * 目标父节点 ID，默认为根目录 0。
     */
    private Long parentId;
}
