package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 视频目录更新入参。
 */
@Data
public class MediaDirectoryUpdateDto {

    /**
     * 目录 ID。
     */
    private String id;

    /**
     * 显示名。
     */
    @NotBlank(message = "显示名不能为空")
    private String name;

    /**
     * 媒体类型：movie / tv / other。
     */
    @NotBlank(message = "媒体类型不能为空")
    @Pattern(regexp = "movie|tv|other", message = "媒体类型仅支持 movie/tv/other")
    private String mediaType;

    /**
     * 定时重扫 cron 表达式，可为空。
     */
    private String scanCron;
}
