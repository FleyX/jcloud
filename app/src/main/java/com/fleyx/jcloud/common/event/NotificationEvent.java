package com.fleyx.jcloud.common.event;

import com.fleyx.jcloud.common.enums.NotificationEventType;
import org.springframework.context.ApplicationEvent;

/**
 * 通知事件（ADR 0039）。
 * <p>
 * 业务触发点发布，通知模块消费后经渠道适配器分发。本工单仅支持用户级收件人（targetUserId），
 * 管理员广播在后续工单扩展。事件必须自包含：标题与内容随事件携带，消费方不回查业务数据。
 */
public class NotificationEvent extends ApplicationEvent {

    /**
     * 事件类型。
     */
    private final NotificationEventType eventType;

    /**
     * 收件用户 ID。
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
     * 创建事件。
     *
     * @param source       事件源
     * @param eventType    事件类型
     * @param targetUserId 收件用户 ID
     * @param title        通知标题
     * @param content      通知内容，可空
     */
    public NotificationEvent(Object source, NotificationEventType eventType, String targetUserId,
                             String title, String content) {
        super(source);
        this.eventType = eventType;
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
     * 获取收件用户 ID。
     *
     * @return 收件用户 ID
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
