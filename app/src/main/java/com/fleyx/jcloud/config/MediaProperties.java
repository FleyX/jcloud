package com.fleyx.jcloud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 视频媒体库相关配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jcloud.media")
public class MediaProperties {

    /**
     * ffmpeg 可执行文件路径，默认从 PATH 查找。
     */
    private String ffmpegPath = "ffmpeg";

    /**
     * ffprobe 可执行文件路径，默认从 PATH 查找。
     */
    private String ffprobePath = "ffprobe";

    /**
     * 硬件加速类型：auto 自动探测 / vaapi / qsv / nvenc / amf / none。
     */
    private String hwaccel = "auto";

    /**
     * 全局最大并发转码进程数，默认等于 CPU 核心数。
     */
    private int transcodeConcurrency = Runtime.getRuntime().availableProcessors();

    /**
     * 转码会话空闲超时时间（分钟），超时无切片请求自动回收。
     */
    private long sessionIdleTimeoutMinutes = 5;

    /**
     * HLS 切片时长（秒）。
     */
    private int hlsSegmentSeconds = 4;

    /**
     * ffprobe 探测超时时间（秒）。
     */
    private long probeTimeoutSeconds = 60;
}
