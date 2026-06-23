package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 权限资源关联表实体。
 */
@Data
@TableName("t_permission_resource")
public class PermissionResource implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 关联 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 权限 ID。
     */
    private Long permissionId;

    /**
     * 资源 ID。
     */
    private Long resourceId;

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;
}
