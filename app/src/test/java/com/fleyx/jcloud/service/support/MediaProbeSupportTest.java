package com.fleyx.jcloud.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.config.MediaProperties;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ffprobe 探测结果解析单元测试（不启动真实 ffprobe 进程）。
 */
class MediaProbeSupportTest {

    private final MediaProbeSupport support = new MediaProbeSupport(new MediaProperties(), new ObjectMapper());

    @Test
    void shouldParseFormatAndTracks() throws Exception {
        String json = """
                {
                  "format": {"format_name": "mov,mp4,m4a,3gp,3g2,mj2", "duration": "12.345", "bit_rate": "1234567"},
                  "streams": [
                    {"codec_type": "video", "codec_name": "h264", "width": 1920, "height": 1080,
                     "disposition": {"default": 1}},
                    {"codec_type": "audio", "codec_name": "aac",
                     "tags": {"language": "eng"}, "disposition": {"default": 1}},
                    {"codec_type": "audio", "codec_name": "ac3",
                     "tags": {"language": "chi", "title": "国语"}, "disposition": {"default": 0}},
                    {"codec_type": "subtitle", "codec_name": "subrip",
                     "tags": {"language": "chi", "title": "简体"}, "disposition": {"default": 1}},
                    {"codec_type": "subtitle", "codec_name": "ass", "disposition": {"default": 0}}
                  ]
                }
                """;
        MediaProbeResult result = support.parse(json);

        assertEquals(12345L, result.durationMs());
        assertEquals("mov", result.container());
        assertEquals(1234567L, result.bitRate());
        assertEquals("h264", result.videoCodec());
        assertEquals(1920, result.width());
        assertEquals(1080, result.height());
        assertEquals("aac", result.audioCodec());

        assertEquals(2, result.audioTracks().size());
        assertTrue(result.audioTracks().get(0).defaulted());
        assertEquals("eng", result.audioTracks().get(0).language());
        assertFalse(result.audioTracks().get(1).defaulted());
        assertEquals("国语", result.audioTracks().get(1).title());

        assertEquals(2, result.subtitleTracks().size());
        MediaProbeResult.Track firstSub = result.subtitleTracks().get(0);
        assertEquals(0, firstSub.index());
        assertEquals("subrip", firstSub.codec());
        assertEquals("chi", firstSub.language());
        assertEquals("简体", firstSub.title());
        assertTrue(firstSub.defaulted());
        assertEquals(1, result.subtitleTracks().get(1).index());
        assertFalse(result.subtitleTracks().get(1).defaulted());
        assertNull(result.subtitleTracks().get(1).language());
    }

    @Test
    void shouldIgnoreUnparsableBitRate() throws Exception {
        String json = """
                {"format": {"format_name": "matroska,webm", "duration": "1.5", "bit_rate": "N/A"}, "streams": []}
                """;
        MediaProbeResult result = support.parse(json);

        assertNull(result.bitRate());
        assertEquals(1500L, result.durationMs());
        assertEquals("matroska", result.container());
    }

    @Test
    void shouldSkipAttachedPicVideoStream() throws Exception {
        String json = """
                {
                  "format": {"format_name": "mp3", "duration": "60.0"},
                  "streams": [
                    {"codec_type": "video", "codec_name": "attached_pic", "width": 500, "height": 500},
                    {"codec_type": "audio", "codec_name": "mp3", "disposition": {"default": 1}}
                  ]
                }
                """;
        MediaProbeResult result = support.parse(json);

        assertNull(result.videoCodec());
        assertNull(result.width());
        assertEquals("mp3", result.audioCodec());
    }

    @Test
    void shouldHandleMissingFormatAndStreams() throws Exception {
        MediaProbeResult result = support.parse("{}");

        assertNull(result.durationMs());
        assertNull(result.container());
        assertNull(result.bitRate());
        assertNull(result.videoCodec());
        assertTrue(result.audioTracks().isEmpty());
        assertTrue(result.subtitleTracks().isEmpty());
    }

    /**
     * PGS/DVD 等位图字幕轨不暴露，文本字幕轨保留并保持原文件字幕流序号
     * （过滤位图轨后序号仍指向 ffmpeg -map 0:s:{index} 的原流序号）。
     */
    @Test
    void shouldFilterBitmapSubtitlesAndKeepTextTrackOriginalStreamIndex() throws Exception {
        String json = """
                {
                  "format": {"format_name": "matroska"},
                  "streams": [
                    {"codec_type": "video", "codec_name": "h264", "width": 1920, "height": 1080},
                    {"codec_type": "subtitle", "codec_name": "subrip",
                     "tags": {"language": "chi"}, "disposition": {"default": 1}},
                    {"codec_type": "subtitle", "codec_name": "hdmv_pgs_subtitle", "disposition": {"default": 0}},
                    {"codec_type": "subtitle", "codec_name": "ass",
                     "tags": {"language": "eng"}, "disposition": {"default": 0}},
                    {"codec_type": "subtitle", "codec_name": "dvd_subtitle", "disposition": {"default": 0}}
                  ]
                }
                """;
        MediaProbeResult result = support.parse(json);

        assertEquals(2, result.subtitleTracks().size());
        MediaProbeResult.Track first = result.subtitleTracks().get(0);
        assertEquals("subrip", first.codec());
        assertEquals(0, first.index());
        assertTrue(first.defaulted());
        MediaProbeResult.Track second = result.subtitleTracks().get(1);
        assertEquals("ass", second.codec());
        // 原文件字幕流序号：subrip=0、pgs=1（被过滤）、ass=2、dvd=3（被过滤）
        assertEquals(2, second.index());
        assertFalse(second.defaulted());
    }
}
