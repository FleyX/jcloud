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

    /**
     * 全局冲突解决策略，用户选择「统一全部」时使用。
     */
    private String globalStrategy;
}
