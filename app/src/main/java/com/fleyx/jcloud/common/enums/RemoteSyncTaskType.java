package com.fleyx.jcloud.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 远程同步任务触发方式。
 */
@Getter
@RequiredArgsConstructor
public enum RemoteSyncTaskType {

    /**
     * 手动触发。
     */
    MANUAL("manual"),

    /**
     * 定时触发。
     */
    SCHEDULED("scheduled");

    private final String value;
}
