package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 通知事件开关入参。
 */
@Data
public class NotificationEventSwitchDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否启用该事件。
     */
    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;
}
