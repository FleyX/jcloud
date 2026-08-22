package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 设备会话视图。
 */
@Data
public class DeviceSessionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 设备标识。
     */
    private String deviceId;

    /**
     * 设备名。
     */
    private String deviceName;

    /**
     * 最近活跃时间（epoch milli）。
     */
    private Long lastActiveTime;

    /**
     * 是否发起请求的当前设备。
     */
    private Boolean current;
}