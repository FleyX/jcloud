package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色表实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_role")
public class Role extends SoftDeleteEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 角色编码，全局唯一。
     */
    private String code;

    /**
     * 角色名称。
     */
    private String name;

    /**
     * 角色描述。
     */
    private String description;

    /**
     * 状态：1 启用，0 禁用。
     */
    private Integer status;
}
