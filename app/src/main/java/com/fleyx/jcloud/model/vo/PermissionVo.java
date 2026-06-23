package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 权限资源视图对象。
 */
@Data
public class PermissionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 权限 ID。
     */
    private Long id;

    /**
     * 权限编码。
     */
    private String code;

    /**
     * 权限名称。
     */
    private String name;
}
