package com.fleyx.jcloud.common.enums;

import lombok.Getter;

/**
 * 通知事件类型（ADR 0039）。
 * <p>
 * 枚举值即 {@code t_notification.event_type} 字符串；标题与内容由业务触发点给定，枚举只做类型标识。
 */
@Getter
public enum NotificationEventType {

    /**
     * 跨来源传输完成（全部成功或部分成功）。
     */
    TRANSFER_COMPLETED("transfer_completed"),

    /**
     * 跨来源传输失败。
     */
    TRANSFER_FAILED("transfer_failed");

    private final String value;

    NotificationEventType(String value) {
        this.value = value;
    }
}
