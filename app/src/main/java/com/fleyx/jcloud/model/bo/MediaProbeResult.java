package com.fleyx.jcloud.model.bo;

import java.util.List;

/**
 * ffprobe 探测结果。
 *
 * @param durationMs 时长（毫秒）
 * @param container  封装格式
 * @param videoCodec 视频编码
 * @param audioCodec 首个音频编码
 * @param width      视频宽度
 * @param height     视频高度
 * @param audioTracks 音轨列表
 * @param subtitleTracks 字幕轨列表
 */
public record MediaProbeResult(
        Long durationMs,
        String container,
        String videoCodec,
        String audioCodec,
        Integer width,
        Integer height,
        List<Track> audioTracks,
        List<Track> subtitleTracks) {

    /**
     * 音视频/字幕轨信息。
     *
     * @param index    在该类型流中的序号（从 0 开始）
     * @param codec    编码
     * @param language 语言，未知为 null
     * @param title    轨道标题，未知为 null
     */
    public record Track(int index, String codec, String language, String title) {
    }
}
