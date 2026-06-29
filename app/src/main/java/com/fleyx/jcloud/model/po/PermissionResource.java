package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 权限资源关联表实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_permission_resource")
public class PermissionResource extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 权限 ID。
     */
    private String permissionId;

    /**
     * 资源 ID。
     */
    private String resourceId;
}
