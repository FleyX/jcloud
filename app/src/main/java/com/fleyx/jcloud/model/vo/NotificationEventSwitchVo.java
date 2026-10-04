package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 通知事件开关视图对象。
 */
@Data
public class NotificationEventSwitchVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 事件类型枚举值，如 transfer_completed。
     */
    private String eventType;

    /**
     * 事件中文名。
     */
    private String name;

    /**
     * 是否启用。
     */
    private Boolean enabled;
}
