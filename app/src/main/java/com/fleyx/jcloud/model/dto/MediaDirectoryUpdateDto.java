package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

/**
 * 媒体库更新入参。
 */
@Data
public class MediaDirectoryUpdateDto {

    /**
     * 媒体库 ID。
     */
    private String id;

    /**
     * 来源目录的文件夹节点 ID 列表（全量），至少 1 个；增删来源目录会中断当前任务并强制全量重扫。
     */
    @NotEmpty(message = "来源目录不能为空")
    private List<@NotBlank(message = "来源目录文件夹节点 ID 不能为空") String> sourceFileNodeIds;

    /**
     * 显示名。
     */
    @NotBlank(message = "显示名不能为空")
    private String name;

    /**
     * 媒体类型：movie / tv / other。媒体类型创建后不可修改，可不传或传原值。
     */
    @Pattern(regexp = "movie|tv|other", message = "媒体类型仅支持 movie/tv/other")
    private String mediaType;

    /**
     * 定时重扫 cron 表达式，可为空。
     */
    private String scanCron;
}
