package com.fleyx.jcloud.common.enums;

/**
 * 媒体目录削刮状态。
 */
public enum MediaScrapeStatus {

    /**
     * 削刮中。
     */
    SCRAPING,

    /**
     * 削刮完成。
     */
    COMPLETED,

    /**
     * 削刮失败。
     */
    FAILED,

    /**
     * 部分条目削刮失败。
     */
    PARTIAL
}
