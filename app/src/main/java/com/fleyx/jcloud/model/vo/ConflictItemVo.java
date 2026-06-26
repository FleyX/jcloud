package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 冲突项视图。
 */
@Data
public class ConflictItemVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 源节点 ID。
     */
    private Long sourceId;

    /**
     * 源节点名称。
     */
    private String sourceName;

    /**
     * 源节点类型：file / folder。
     */
    private String sourceType;

    /**
     * 目标已存在节点 ID。
     */
    private Long existingId;

    /**
     * 目标已存在节点名称。
     */
    private String existingName;

    /**
     * 目标已存在节点类型：file / folder。
     */
    private String existingType;
}
