package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 角色视图对象。
 */
@Data
public class RoleVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 角色 ID。
     */
    private Long id;

    /**
     * 角色编码。
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
}
