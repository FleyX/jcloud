package com.fleyx.jcloud.common.enums;

import lombok.Getter;

/**
 * 通用启用/禁用状态枚举。
 */
@Getter
public enum CommonStatus {

    /**
     * 启用。
     */
    ENABLED(1, "启用"),

    /**
     * 禁用。
     */
    DISABLED(0, "禁用");

    private final int code;
    private final String desc;

    CommonStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
