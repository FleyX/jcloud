package com.fleyx.jcloud.model.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 设备会话业务对象，对应 Redis 中的会话记录。
 */
@Data
public class AuthSession implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID。
     */
    private String userId;

    /**
     * 用户编码（username）。
     */
    private String userCode;

    /**
     * 设备标识。
     */
    private String deviceId;

    /**
     * 设备名。
     */
    private String deviceName;

    /**
     * 当前刷新令牌的 SHA-256 hex。
     */
    private String tokenHash;

    /**
     * 创建时间（epoch milli）。
     */
    private long createTime;

    /**
     * 最近活跃时间（epoch milli）。
     */
    private long lastActiveTime;
}
