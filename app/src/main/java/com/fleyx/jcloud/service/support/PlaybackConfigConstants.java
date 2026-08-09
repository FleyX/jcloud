package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.model.vo.MediaPlaybackConfigVo;

import java.util.List;
import java.util.Set;

/**
 * 全局播放配置契约常量（ADR 0024）：码率档位阶梯与直放白名单的唯一事实源。
 * <p>
 * 转封装白名单单一定义在 {@link TranscodeCommandBuilder}，看完阈值单一定义在
 * {@link MediaHomeItemSupport}，本类承载档位阶梯、直放白名单与直放判定，
 * 并装配全局播放配置接口（/api/media/playback-config）的响应。
 */
public final class PlaybackConfigConstants {

    private PlaybackConfigConstants() {
    }

    /**
     * 直放白名单：浏览器可原生硬解的容器/视频编码/音频编码（直放判定与接口下发共用）。
     */
    public static final Set<String> DIRECT_CONTAINERS = Set.of("mp4", "mov", "m4v", "webm");
    public static final Set<String> DIRECT_VIDEO_CODECS = Set.of("h264", "hevc", "vp8", "vp9", "av1");
    public static final Set<String> DIRECT_AUDIO_CODECS = Set.of("aac", "mp3", "opus", "vorbis", "flac");

    /**
     * 码率档位阶梯（七档）：所选档位码率低于文件实际码率时强制实时转码并按档位输出，
     * 等于或高于实际码率时优先直放/原质量转码；kbps/maxHeight 为 null 表示原画。
     */
    public static final List<MediaPlaybackConfigVo.BitrateTierVo> BITRATE_TIERS = List.of(
            new MediaPlaybackConfigVo.BitrateTierVo("original", "原画", null, null),
            new MediaPlaybackConfigVo.BitrateTierVo("20000-2160", "20M · 4K", 20000, 2160),
            new MediaPlaybackConfigVo.BitrateTierVo("8000-1080", "8M · 1080p", 8000, 1080),
            new MediaPlaybackConfigVo.BitrateTierVo("4000-1080", "4M · 1080p", 4000, 1080),
            new MediaPlaybackConfigVo.BitrateTierVo("2000-720", "2M · 720p", 2000, 720),
            new MediaPlaybackConfigVo.BitrateTierVo("1000-480", "1M · 480p", 1000, 480),
            new MediaPlaybackConfigVo.BitrateTierVo("500-360", "500K · 360p", 500, 360));

    /**
     * 是否可硬解直放：容器与视频编码命中白名单，音频编码为空或命中白名单；mov 容器按 mp4 处理。
     */
    public static boolean canDirectPlay(String container, String videoCodec, String audioCodec) {
        if (container == null || videoCodec == null) {
            return false;
        }
        String normalizedContainer = container.toLowerCase();
        // mov 容器按 mp4 处理
        if ("mov".equals(normalizedContainer)) {
            normalizedContainer = "mp4";
        }
        return DIRECT_CONTAINERS.contains(normalizedContainer)
                && DIRECT_VIDEO_CODECS.contains(videoCodec.toLowerCase())
                && (audioCodec == null || DIRECT_AUDIO_CODECS.contains(audioCodec.toLowerCase()));
    }

    /**
     * 装配全局播放配置接口响应（档位/直放白名单/转封装白名单/看完阈值）。
     */
    public static MediaPlaybackConfigVo buildConfig() {
        return new MediaPlaybackConfigVo(BITRATE_TIERS,
                new MediaPlaybackConfigVo.DirectPlayVo(DIRECT_CONTAINERS, DIRECT_VIDEO_CODECS, DIRECT_AUDIO_CODECS),
                new MediaPlaybackConfigVo.RemuxVo(TranscodeCommandBuilder.VIDEO_COPY_CODECS,
                        TranscodeCommandBuilder.AUDIO_COPY_CODECS),
                MediaHomeItemSupport.FINISHED_RATIO);
    }
}
