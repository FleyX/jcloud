package com.fleyx.jcloud.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量上传前冲突预检请求参数。
 */
@Data
public class BatchUploadPreCheckDto {

    /**
     * 预检请求项列表。
     */
    private List<FileUploadPreCheckDto> items;
}
