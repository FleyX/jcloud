package com.fleyx.jcloud.common.enums;

import lombok.Getter;

/**
 * 媒体元数据完整性状态枚举。
 */
@Getter
public enum MediaCompleteStatus {

    /**
     * 完整。
     */
    COMPLETE("complete"),

    /**
     * 不完整（本地优先缺字段不补）。
     */
    INCOMPLETE("incomplete");

    private final String code;

    MediaCompleteStatus(String code) {
        this.code = code;
    }
}
