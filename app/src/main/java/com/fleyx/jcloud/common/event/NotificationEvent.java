package com.fleyx.jcloud.common.event;

import com.fleyx.jcloud.common.enums.NotificationEventType;
import com.fleyx.jcloud.common.enums.NotificationTargetType;
import org.springframework.context.ApplicationEvent;

/**
 * 通知事件（ADR 0039）。
 * <p>
 * 业务触发点发布，通知模块消费后经渠道适配器分发。收件人分两类：用户级（targetUserId）与
 * 管理员广播（全部超级管理员角色用户）。事件必须自包含：标题与内容随事件携带，消费方不回查业务数据。
 */
public class NotificationEvent extends ApplicationEvent {

    /**
     * 事件类型。
     */
    private final NotificationEventType eventType;

    /**
     * 收件人类型：user 或 admins。
     */
    private final NotificationTargetType targetType;

    /**
     * 收件用户 ID，广播给管理员时为空。
     */
    private final String targetUserId;

    /**
     * 通知标题。
     */
    private final String title;

    /**
     * 通知内容，可空。
     */
    private final String content;

    /**
     * 创建用户级通知事件。
     *
     * @param source       事件源
     * @param eventType    事件类型
     * @param targetUserId 收件用户 ID
     * @param title        通知标题
     * @param content      通知内容，可空
     */
    public NotificationEvent(Object source, NotificationEventType eventType, String targetUserId,
                             String title, String content) {
        this(source, eventType, NotificationTargetType.USER, targetUserId, title, content);
    }

    /**
     * 创建通知事件。
     *
     * @param source       事件源
     * @param eventType    事件类型
     * @param targetType   收件人类型
     * @param targetUserId 收件用户 ID，管理员广播时为空
     * @param title        通知标题
     * @param content      通知内容，可空
     */
    public NotificationEvent(Object source, NotificationEventType eventType, NotificationTargetType targetType,
                             String targetUserId, String title, String content) {
        super(source);
        this.eventType = eventType;
        this.targetType = targetType;
        this.targetUserId = targetUserId;
        this.title = title;
        this.content = content;
    }

    /**
     * 获取事件类型。
     *
     * @return 事件类型
     */
    public NotificationEventType getEventType() {
        return eventType;
    }

    /**
     * 获取收件人类型。
     *
     * @return 收件人类型
     */
    public NotificationTargetType getTargetType() {
        return targetType;
    }

    /**
     * 获取收件用户 ID。
     *
     * @return 收件用户 ID，管理员广播时为空
     */
    public String getTargetUserId() {
        return targetUserId;
    }

    /**
     * 获取通知标题。
     *
     * @return 通知标题
     */
    public String getTitle() {
        return title;
    }

    /**
     * 获取通知内容。
     *
     * @return 通知内容，可空
     */
    public String getContent() {
        return content;
    }
}
