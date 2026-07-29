package com.fleyx.jcloud.common.enums;

/**
 * 媒体目录扫描状态。
 */
public enum MediaScanStatus {

    /**
     * 扫描中。
     */
    SCANNING,

    /**
     * 扫描完成。
     */
    COMPLETED,

    /**
     * 扫描失败。
     */
    FAILED,

    /**
     * 部分文件扫描失败。
     */
    PARTIAL
}
