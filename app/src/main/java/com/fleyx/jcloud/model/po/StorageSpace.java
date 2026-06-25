package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 存储空间实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_storage_space")
public class StorageSpace extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 存储空间名称。
     */
    private String name;

    /**
     * 物理路径。
     */
    private String path;

    /**
     * 类型：USER / SYSTEM。
     */
    private String type;

    /**
     * 容量（字节）。
     */
    private Long capacity;

    /**
     * 已用空间（字节）。
     */
    private Long usedSpace;

    /**
     * 状态：1 启用，0 禁用。
     */
    private Integer status;

    /**
     * 备注。
     */
    private String remark;
}
