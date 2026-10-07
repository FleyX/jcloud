package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 邮件发送记录实体。
 * <p>
 * 仅追加不清理（本期保留全部记录），故不继承 {@link SoftDeleteEntity}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_notification_log")
public class NotificationLog extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 事件类型（含 email_share 邮件分享）。
     */
    private String eventType;

    /**
     * 收件邮箱地址。
     */
    private String recipient;

    /**
     * 邮件主题。
     */
    private String subject;

    /**
     * 发送结果：1 成功，0 失败。
     */
    private Integer success;

    /**
     * 失败原因，成功时为空。
     */
    private String errorMessage;
}
