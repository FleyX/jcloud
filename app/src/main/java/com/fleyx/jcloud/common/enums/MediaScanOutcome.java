package com.fleyx.jcloud.common.enums;

/**
 * 单轮媒体库扫描结果（扫描编排内部使用，与落库的 {@link MediaScanStatus} 区分）。
 */
public enum MediaScanOutcome {

    /**
     * 完整成功。
     */
    COMPLETED,

    /**
     * 部分失败（含来源目录不可达）。
     */
    PARTIAL,

    /**
     * 被中断。
     */
    CANCELLED
}
