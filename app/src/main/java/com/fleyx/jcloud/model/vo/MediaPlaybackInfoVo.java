package com.fleyx.jcloud.model.vo;

import com.fleyx.jcloud.model.bo.MediaProbeResult;
import lombok.Data;

import java.util.List;

/**
 * 播放信息视图。
 */
@Data
public class MediaPlaybackInfoVo {

    /**
     * 播放模式：direct 直放 / transcode 转码。
     */
    private String mode;

    /**
     * 直放地址（mode=direct 时有效）。
     */
    private String directUrl;

    /**
     * 转码播放列表地址（mode=transcode 时有效）。
     */
    private String transcodeUrl;

    /**
     * 时长（毫秒）。
     */
    private Long durationMs;

    /**
     * 封装格式。
     */
    private String container;

    /**
     * 视频编码。
     */
    private String videoCodec;

    /**
     * 音频编码。
     */
    private String audioCodec;

    /**
     * 视频宽度。
     */
    private Integer width;

    /**
     * 视频高度。
     */
    private Integer height;

    /**
     * 音轨列表。
     */
    private List<MediaProbeResult.Track> audioTracks;

    /**
     * 字幕轨列表。
     */
    private List<MediaProbeResult.Track> subtitleTracks;

    /**
     * 播放进度（毫秒）。
     */
    private Long progressMs;
}
