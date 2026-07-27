package com.fleyx.jcloud.model.dto;

import lombok.Data;

/**
 * 视频转码全局配置（管理员）。
 */
@Data
public class TranscodeConfigDto {

    /**
     * 硬件加速方式：auto 自动探测 / vaapi / qsv / nvenc / none 软解。
     */
    private String hwaccel;

    /**
     * 硬件设备路径，仅 vaapi 使用，默认 /dev/dri/renderD128。
     */
    private String device;

    /**
     * ffmpeg 单任务线程数（-threads），0 表示 ffmpeg 自动。
     */
    private Integer threads;
}
