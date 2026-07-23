package com.fleyx.jcloud.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 同步任务触发方式（远程挂载同步、用户存储空间同步通用）。
 */
@Getter
@RequiredArgsConstructor
public enum SyncTaskType {

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
