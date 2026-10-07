package com.fleyx.jcloud.common.enums;

import lombok.Getter;

/**
 * 通知收件人类型（ADR 0039）。
 * <p>
 * {@link #USER} 为单个用户；{@link #ADMINS} 为广播给全部超级管理员角色用户。
 */
@Getter
public enum NotificationTargetType {

    /**
     * 单个用户，收件人取 targetUserId。
     */
    USER("user"),

    /**
     * 全部超级管理员角色用户。
     */
    ADMINS("admins");

    private final String value;

    NotificationTargetType(String value) {
        this.value = value;
    }
}
