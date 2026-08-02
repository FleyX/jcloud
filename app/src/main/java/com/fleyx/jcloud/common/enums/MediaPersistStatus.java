package com.fleyx.jcloud.common.enums;

import lombok.Getter;

/**
 * 媒体元数据落盘状态枚举（写回视频目录的 NFO/图片）。
 */
@Getter
public enum MediaPersistStatus {

    /**
     * 待落盘。
     */
    PENDING("pending"),

    /**
     * 已写回视频目录。
     */
    PERSISTED("persisted"),

    /**
     * 落盘失败待重试。
     */
    FAILED("failed");

    private final String code;

    MediaPersistStatus(String code) {
        this.code = code;
    }
}
