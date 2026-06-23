package com.fleyx.jcloud.common.enums;

import lombok.Getter;

/**
 * 用户状态枚举。
 */
@Getter
public enum UserStatus {

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

    UserStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
