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
     * 硬件加速类型：vaapi / qsv / nvenc / none 软解（无 auto 档）。
     * yml 默认值保留为 auto，仅作为首次启动探测完成前的兜底，探测落库后以系统配置表为准。
     */
    private String hwaccel = "auto";

    /**
     * 是否在应用启动时执行硬解方式真实编码探测（首次探测落库后不再自动探测）。
     */
    private boolean hwaccelProbeEnabled = true;

    /**
     * 全局最大并发转码进程数，默认等于 CPU 核心数。
     */
    private int transcodeConcurrency = Runtime.getRuntime().availableProcessors();

    /**
     * 转码会话空闲超时时间（分钟），超时无切片请求自动回收。
     */
    private long sessionIdleTimeoutMinutes = 5;

    /**
     * 心跳会话超时时间（秒）：收到过播放页心跳的会话，超过该时间无新心跳视为播放已结束，立即回收。
     * 需大于前端心跳间隔（5s），并容忍浏览器后台标签页定时器节流（最差约 60s 一次）。
     */
    private long sessionHeartbeatTimeoutSeconds = 90;

    /**
     * HLS 切片时长（秒）。
     */
    private int hlsSegmentSeconds = 4;

    /**
     * ffprobe 探测超时时间（秒）。
     */
    private long probeTimeoutSeconds = 60;

    /**
     * 转码节流：ffmpeg 已生成时长领先客户端请求时长大于等于该值时暂停进程（秒）。
     */
    private int transcodeThrottleAheadSeconds = 600;

    /**
     * 转码节流：暂停后领先量回落到小于等于该值时继续进程（秒）。
     */
    private int transcodeThrottleResumeSeconds = 300;

    /**
     * 影视库文件变更防抖窗口（秒）：距最后一条匹配成功的文件变更事件无新事件时才触发库级扫描。
     */
    private int fileChangeDebounceSeconds = 30;
}
