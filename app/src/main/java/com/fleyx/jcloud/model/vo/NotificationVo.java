package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站内通知视图对象。
 */
@Data
public class NotificationVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 通知 ID。
     */
    private String id;

    /**
     * 事件类型。
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
     * 是否已读。
     */
    private Boolean isRead;

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;
}
