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
     * 字幕轨列表（内嵌，实时 ffprobe 探测）。
     */
    private List<MediaProbeResult.Track> subtitleTracks;

    /**
     * 统一字幕列表（内嵌轨在前按 index，外部字幕在后按默认优先、标签排序），
     * 前端据此渲染「无 + 全部字幕」选择列表。
     */
    private List<MediaSubtitleItemVo> subtitles;

    /**
     * 实际码率（bps）：优先 ffprobe bit_rate，缺失时按文件大小与时长估算，均缺失为 null。
     */
    private Long effectiveBitRate;

    /**
     * 播放进度（毫秒）。
     */
    private Long progressMs;

    /**
     * 本次解析使用的文件明细行 ID（电影为 t_media_movie_file 明细行 ID，即版本 ID；
     * 集为 t_media_episode_file 明细行 ID；其他为 other 行 ID）。前端播放时以此定位版本，
     * 进度上报时回传该值使 last_play_file_id 记为该版本（续播定位）。
     */
    private String versionId;

    /**
     * 文件节点名（纯播放模式作标题栏展示；影视模式前端忽略）。
     */
    private String fileName;
}
