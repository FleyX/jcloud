package com.fleyx.jcloud.model.vo;

import java.io.Serial;
import java.io.Serializable;

/**
 * 资源视图（只读，数据源为内存 PermissionRegistry）。
 *
 * @param code 资源编码（API 资源为 METHOD:path，前端资源以 VIEW: 为前缀）
 * @param name 资源名称
 */
public record ResourceVo(String code, String name) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
