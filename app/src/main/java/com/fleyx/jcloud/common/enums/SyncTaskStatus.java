package com.fleyx.jcloud.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 同步任务状态（远程挂载同步、用户存储空间同步、用户迁移通用）。
 */
@Getter
@RequiredArgsConstructor
public enum SyncTaskStatus {

    /**
     * 待执行。
     */
    PENDING("PENDING"),

    /**
     * 执行中。
     */
    RUNNING("RUNNING"),

    /**
     * 已完成。
     */
    COMPLETED("COMPLETED"),

    /**
     * 失败。
     */
    FAILED("FAILED"),

    /**
     * 部分成功。
     */
    PARTIAL("PARTIAL");

    private final String value;
}
