package com.fleyx.jcloud.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量分片上传初始化请求参数。
 */
@Data
public class BatchChunkedUploadInitDto {

    /**
     * 分片上传初始化请求项列表。
     */
    private List<ChunkedUploadInitDto> items;
}
