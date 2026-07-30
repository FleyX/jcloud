package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 媒体库视图。
 */
@Data
public class MediaDirectoryVo {

    /**
     * 媒体库 ID。
     */
    private String id;

    /**
     * 来源目录列表。
     */
    private List<MediaDirectorySourceVo> sources;

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

    /**
     * 封面图 URL（库内最新添加且有海报/缩略图的条目，无条目时为空）。
     */
    private String coverPosterUrl;
}
