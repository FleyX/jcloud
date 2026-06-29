package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色权限关联表实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_role_permission")
public class RolePermission extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 角色 ID。
     */
    private String roleId;

    /**
     * 权限 ID。
     */
    private String permissionId;
}
