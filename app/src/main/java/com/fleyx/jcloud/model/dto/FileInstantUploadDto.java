package com.fleyx.jcloud.model.dto;

import lombok.Data;

/**
 * 秒传请求参数。
 */
@Data
public class FileInstantUploadDto {

    /**
     * 候选文件节点 ID。
     */
    private Long candidateId;

    /**
     * 完整文件 hash。
     */
    private String fullHash;

    /**
     * 新文件名。
     */
    private String fileName;

    /**
     * 目标父节点 ID，默认为根目录 0。
     */
    private Long parentId;
}
