package com.fleyx.jcloud.model.dto;

import lombok.Data;

/**
 * 秒传预检查请求参数。
 */
@Data
public class FilePreCheckDto {

    /**
     * 文件名。
     */
    private String fileName;

    /**
     * 文件大小（字节）。
     */
    private Long size;

    /**
     * 文件身份 hash（小于 150MB 为完整 hash，大于等于 150MB 为采样 hash）。
     */
    private String partialHash;
}
