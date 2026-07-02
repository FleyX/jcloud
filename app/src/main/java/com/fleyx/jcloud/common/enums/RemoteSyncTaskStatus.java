package com.fleyx.jcloud.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 远程同步任务状态。
 */
@Getter
@RequiredArgsConstructor
public enum RemoteSyncTaskStatus {

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
