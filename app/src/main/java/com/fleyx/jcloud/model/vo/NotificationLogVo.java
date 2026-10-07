package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 邮件发送记录视图对象。
 */
@Data
public class NotificationLogVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 记录 ID。
     */
    private String id;

    /**
     * 事件类型枚举值。
     */
    private String eventType;

    /**
     * 事件中文名；非通知事件类型（如 email_share）回退为枚举值。
     */
    private String eventTypeName;

    /**
     * 收件邮箱地址。
     */
    private String recipient;

    /**
     * 邮件主题。
     */
    private String subject;

    /**
     * 是否发送成功。
     */
    private Boolean success;

    /**
     * 失败原因，成功时为空。
     */
    private String errorMessage;

    /**
     * 发送时间。
     */
    private LocalDateTime createTime;
}
