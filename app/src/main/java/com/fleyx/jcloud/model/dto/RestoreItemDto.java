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
    private String id;

    /**
     * 冲突解决策略：skip / overwrite / keep。
     */
    private String strategy;
}
