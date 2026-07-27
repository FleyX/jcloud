package com.fleyx.jcloud.common.enums;

import lombok.Getter;

/**
 * 媒体条目元数据匹配状态枚举。
 */
@Getter
public enum MediaMatchStatus {

    /**
     * 自动匹配成功。
     */
    MATCHED("matched"),

    /**
     * 手动修正匹配。
     */
    MANUAL("manual"),

    /**
     * 未识别。
     */
    UNMATCHED("unmatched"),

    /**
     * 无需匹配（其他类型）。
     */
    NONE("none");

    private final String code;

    MediaMatchStatus(String code) {
        this.code = code;
    }
}
