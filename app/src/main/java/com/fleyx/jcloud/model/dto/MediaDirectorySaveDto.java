package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

/**
 * 媒体库保存入参。
 */
@Data
public class MediaDirectorySaveDto {

    /**
     * 来源目录的文件夹节点 ID 列表，至少 1 个。
     */
    @NotEmpty(message = "来源目录不能为空")
    private List<@NotBlank(message = "来源目录文件夹节点 ID 不能为空") String> sourceFileNodeIds;

    /**
     * 显示名，为空取第一个来源目录文件夹名。
     */
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
