package com.fleyx.jcloud.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 恢复执行参数。
 */
@Data
public class FileExecuteRestoreDto {

    /**
     * 恢复项列表。
     */
    private List<RestoreItemDto> items;
}
