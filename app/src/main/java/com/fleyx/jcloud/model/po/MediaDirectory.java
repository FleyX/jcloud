package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 媒体库实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_media_directory")
public class MediaDirectory extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属用户 ID。
     */
    private String userId;

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
     * 下次定时重扫时间。
     */
    private LocalDateTime nextScanTime;

    /**
     * 上次扫描完成时间。
     */
    private LocalDateTime lastScanTime;

    /**
     * 上次扫描状态：COMPLETED / FAILED / PARTIAL。
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
     * 上次削刮状态：SCRAPING / COMPLETED / FAILED / PARTIAL。
     */
    private String lastScrapeStatus;

    /**
     * 上次削刮错误信息。
     */
    private String lastScrapeError;
}
