package com.fleyx.jcloud.model.dto;

import lombok.Data;

/**
 * 恢复项。
 */
@Data
public class RestoreItemDto {

    /**
     * 回收站记录 ID。
     */
    private Long id;

    /**
     * 冲突解决策略：skip / overwrite / auto_rename。
     */
    private String strategy;
}
