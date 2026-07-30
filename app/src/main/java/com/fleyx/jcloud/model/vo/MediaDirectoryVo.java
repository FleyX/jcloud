package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频目录视图。
 */
@Data
public class MediaDirectoryVo {

    /**
     * 目录 ID。
     */
    private String id;

    /**
     * 文件夹节点 ID。
     */
    private String fileNodeId;

    /**
     * 显示名。
     */
    private String name;

    /**
     * 媒体类型：movie / tv / other。
     */
    private String mediaType;

    /**
     * 定时重扫 cron 表达式。
     */
    private String scanCron;

    /**
     * 上次扫描完成时间。
     */
    private LocalDateTime lastScanTime;

    /**
     * 上次扫描状态。
     */
    private String lastScanStatus;

    /**
     * 上次扫描错误信息。
     */
    private String lastScanError;

    /**
     * 上次削刮完成时间。
     */
    private LocalDateTime lastScrapeTime;

    /**
     * 上次削刮状态。
     */
    private String lastScrapeStatus;

    /**
     * 上次削刮错误信息。
     */
    private String lastScrapeError;

    /**
     * 条目数量。
     */
    private Long itemCount;
}
