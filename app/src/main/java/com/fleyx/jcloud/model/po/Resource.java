package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 资源表实体。
 * 一个资源对应一个接口或前端权限 key。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_resource")
public class Resource extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 资源编码，全局唯一，例如 GET:/jcloud/api/users。
     */
    private String code;

    /**
     * 资源名称。
     */
    private String name;

    /**
     * 资源类型：PUBLIC / PAGE / LOGIN / API。
     */
    private String type;

    /**
     * 状态：1 启用，0 禁用。
     */
    private Integer status;
}
