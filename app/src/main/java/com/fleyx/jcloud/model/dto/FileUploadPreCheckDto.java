package com.fleyx.jcloud.model.dto;

import lombok.Data;

/**
 * 上传前冲突预检请求参数。
 */
@Data
public class FileUploadPreCheckDto {

    /**
     * 待上传文件名。
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

    /**
     * 文件身份 hash（小于 150MB 为完整 hash，大于等于 150MB 为采样 hash）。
     * <p>
     * 用于秒传候选文件查询；为空时只进行命名冲突预检。
     */
    private String partialHash;
}
