package com.fleyx.jcloud.common.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;

/**
 * 通知事件类型（ADR 0039）。
 * <p>
 * 枚举值即 {@code t_notification.event_type} 字符串；标题与内容由业务触发点给定，枚举只做类型标识。
 * 显示名用于管理端「通知事件」开关区块。
 */
@Getter
public enum NotificationEventType {

    /**
     * 跨来源传输完成（全部成功或部分成功）。
     */
    TRANSFER_COMPLETED("transfer_completed", "跨来源传输完成"),

    /**
     * 跨来源传输失败。
     */
    TRANSFER_FAILED("transfer_failed", "跨来源传输失败"),

    /**
     * 用户配额告警。
     */
    QUOTA_ALERT("quota_alert", "配额告警"),

    /**
     * 远程同步失败。
     */
    REMOTE_SYNC_FAILED("remote_sync_failed", "远程同步失败"),

    /**
     * 媒体库扫描失败。
     */
    MEDIA_SCAN_FAILED("media_scan_failed", "媒体库扫描失败"),

    /**
     * 新用户注册。
     */
    USER_REGISTERED("user_registered", "新用户注册"),

    /**
     * 存储空间容量告警。
     */
    STORAGE_CAPACITY_ALERT("storage_capacity_alert", "存储空间容量告警"),

    /**
     * 邮件分享失败（附件直发异步发送失败时补发给操作者）。
     */
    EMAIL_SHARE_FAILED("email_share_failed", "邮件分享失败");

    private final String value;

    private final String displayName;

    NotificationEventType(String value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    /**
     * 按枚举值解析事件类型。
     *
     * @param value 枚举值（如 transfer_completed）
     * @return 匹配的事件类型，无匹配时返回空
     */
    public static Optional<NotificationEventType> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst();
    }
}
