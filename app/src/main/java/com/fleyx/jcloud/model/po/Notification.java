package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 站内通知实体。
 * <p>
 * 每用户仅保留最近 100 条，超出由站内渠道物理删除，故不继承 {@link SoftDeleteEntity}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_notification")
public class Notification extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 收件用户 ID。
     */
    private String userId;

    /**
     * 事件类型（NotificationEventType.value）。
     */
    private String eventType;

    /**
     * 通知标题。
     */
    private String title;

    /**
     * 通知内容。
     */
    private String content;

    /**
     * 是否已读：1 已读，0 未读。
     */
    private Integer isRead;
}
