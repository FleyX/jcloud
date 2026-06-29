package com.fleyx.jcloud.model.dto;

import lombok.Data;

/**
 * 操作项。
 */
@Data
public class OperationItemDto {

    /**
     * 源节点 ID。
     */
    private String id;

    /**
     * 源节点名称，预检时用于冲突检测。
     */
    private String name;

    /**
     * 冲突解决策略：skip / overwrite / keep。
     */
    private String strategy;

    /**
     * 自定义新名称（可选）。
     */
    private String newName;
}
