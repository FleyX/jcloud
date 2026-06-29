package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 权限表实体。
 * 权限表仅保留树形层级信息，不存储页面 URL；URL 通过 t_permission_resource 关联到 t_resource。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_permission")
public class Permission extends SoftDeleteEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 权限编码，全局唯一，如 user:menu。
     */
    private String code;

    /**
     * 权限名称。
     */
    private String name;

    /**
     * 父级权限 ID，用于菜单层级。
     */
    private String parentId;

    /**
     * 状态：1 启用，0 禁用。
     */
    private Integer status;
}
