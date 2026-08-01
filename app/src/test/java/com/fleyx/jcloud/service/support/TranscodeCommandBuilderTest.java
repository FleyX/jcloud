package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.config.MediaProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转封装会话命令构建器单元测试（不启动真实 ffmpeg 进程）。
 */
class TranscodeCommandBuilderTest {

    private final TranscodeCommandBuilder builder = new TranscodeCommandBuilder(new MediaProperties());

    private TranscodeCommandBuilder.TranscodeRequest request(String videoCodec, String audioCodec,
                                                             Long targetBitrateKbps, Integer maxHeight,
                                                             boolean forceVideoTranscode) {
        return new TranscodeCommandBuilder.TranscodeRequest(0, null, Path.of("/data/movie.mkv"), null,
                videoCodec, audioCodec, targetBitrateKbps, maxHeight, forceVideoTranscode);
    }

    @Test
    void testVideoCopyEligibility() {
        // 白名单编码可转封装
        assertTrue(builder.isVideoCopyEligible("h264", false, null));
        assertTrue(builder.isVideoCopyEligible("hevc", false, null));
        assertTrue(builder.isVideoCopyEligible("vp9", false, null));
        assertTrue(builder.isVideoCopyEligible("av1", false, null));
        assertTrue(builder.isVideoCopyEligible("H264", false, null));
        // vp8 进 fMP4 兼容性差，排除在 copy 名单外
        assertFalse(builder.isVideoCopyEligible("vp8", false, null));
        assertFalse(builder.isVideoCopyEligible("mpeg2video", false, null));
        assertFalse(builder.isVideoCopyEligible(null, false, null));
        // 强制转码或要求降码率时不可转封装
        assertFalse(builder.isVideoCopyEligible("h264", true, null));
        assertFalse(builder.isVideoCopyEligible("h264", false, 2000L));
    }

    @Test
    void testAudioCopyEligibility() {
        assertTrue(builder.isAudioCopyEligible("aac"));
        assertTrue(builder.isAudioCopyEligible("mp3"));
        assertTrue(builder.isAudioCopyEligible("AAC"));
        assertFalse(builder.isAudioCopyEligible("ac3"));
        assertFalse(builder.isAudioCopyEligible("dts"));
        assertFalse(builder.isAudioCopyEligible("flac"));
        assertFalse(builder.isAudioCopyEligible(null));
    }

    @Test
    void shouldBuildRemuxCommandWhenVideoAndAudioCopy() {
        List<String> command = builder.buildCommand(request("h264", "aac", null, null, false),
                TranscodeCommandBuilder.ENCODER_COPY, "/dev/dri/renderD128", 4, Path.of("/out/session1"));
        String joined = String.join(" ", command);

        assertTrue(joined.contains("-c:v copy"));
        assertTrue(joined.contains("-c:a copy"));
        // 转封装不附加解码/编码相关参数
        assertFalse(joined.contains("-vaapi_device"));
        assertFalse(joined.contains("-crf"));
        assertFalse(joined.contains("-global_quality"));
        assertFalse(joined.contains("-b:v"));
        assertFalse(joined.contains("-threads"));
        assertTrue(joined.contains("-hls_list_size 0"));
        assertTrue(joined.contains("-hls_segment_type fmp4"));
        assertTrue(joined.contains("index.m3u8"));
    }

    @Test
    void shouldBuildBitrateLimitedTranscodeCommand() {
        List<String> command = builder.buildCommand(request("hevc", "ac3", 2000L, 720, false),
                "libx264", "/dev/dri/renderD128", 0, Path.of("/out/session2"));
        String joined = String.join(" ", command);

        assertTrue(joined.contains("-c:v libx264"));
        assertTrue(joined.contains("-preset veryfast -crf 23"));
        assertTrue(joined.contains("-b:v 2000k -maxrate 2000k -bufsize 4000k"));
        // 不放大 scale 表达式
        assertTrue(joined.contains("-vf scale=-2:min(720\\,ih)"));
        // ac3 音轨统一转 AAC 128k
        assertTrue(joined.contains("-c:a aac -b:a 128k -ac 2"));
    }

    @Test
    void shouldApplyScaleWithoutBitrate() {
        // 仅有 maxHeight 无 targetBitrateKbps 时也应用 scale
        List<String> command = builder.buildCommand(request("vp8", "aac", null, 1080, false),
                "libx264", "/dev/dri/renderD128", 0, Path.of("/out/session3"));
        String joined = String.join(" ", command);

        assertTrue(joined.contains("-vf scale=-2:min(1080\\,ih)"));
        assertFalse(joined.contains("-b:v"));
        assertTrue(joined.contains("-c:a copy"));
    }

    @Test
    void shouldForceTranscodeWhenRequested() {
        List<String> command = builder.buildCommand(request("h264", "aac", null, null, true),
                "libx264", "/dev/dri/renderD128", 0, Path.of("/out/session4"));
        String joined = String.join(" ", command);

        assertTrue(joined.contains("-c:v libx264"));
        assertTrue(joined.contains("-crf 23"));
        assertFalse(joined.contains("-c:v copy"));
    }

    @Test
    void shouldBuildVaapiCommandWithScaleBeforeHwupload() {
        List<String> command = builder.buildCommand(request("mpeg2video", "mp3", 8000L, 1080, false),
                "h264_vaapi", "/dev/dri/renderD129", 0, Path.of("/out/session5"));
        String joined = String.join(" ", command);

        assertTrue(joined.contains("-vaapi_device /dev/dri/renderD129"));
        // 软解缩放后再上传硬解帧
        assertTrue(joined.contains("-vf scale=-2:min(1080\\,ih),format=nv12,hwupload"));
        assertTrue(joined.contains("-b:v 8000k -maxrate 8000k -bufsize 16000k"));
        assertTrue(joined.contains("-c:a copy"));
    }

    @Test
    void shouldBuildSeekAudioIndexAndRemoteInput() {
        TranscodeCommandBuilder.TranscodeRequest request = new TranscodeCommandBuilder.TranscodeRequest(
                90_500, 1, null, () -> null, "h264", "aac", null, null, false);
        List<String> command = builder.buildCommand(request, TranscodeCommandBuilder.ENCODER_COPY,
                "/dev/dri/renderD128", 0, Path.of("/out/session6"));
        String joined = String.join(" ", command);

        assertTrue(joined.contains("-ss 90.500"));
        assertTrue(joined.contains("-i pipe:0"));
        assertTrue(joined.contains("-map 0:a:1"));
    }

    @Test
    void shouldReencodeAudioWhenVideoTranscodeWithSeek() {
        // 视频转码 + seek：精确 seek 只裁剪解码流，音频 copy 会停留在 seek 点前关键帧导致音画错位数秒，
        // 必须重编码音频随视频一起裁剪到 seek 点
        TranscodeCommandBuilder.TranscodeRequest request = new TranscodeCommandBuilder.TranscodeRequest(
                1_717_501, null, Path.of("/data/movie.mp4"), null, "hevc", "aac", 1000L, 480, false);
        List<String> command = builder.buildCommand(request, "libx264",
                "/dev/dri/renderD128", 0, Path.of("/out/session7"));
        String joined = String.join(" ", command);

        assertTrue(joined.contains("-ss 1717.501"));
        assertTrue(joined.contains("-c:a aac -b:a 128k -ac 2"));
        assertFalse(joined.contains("-c:a copy"));
    }

    @Test
    void shouldDisableAccurateSeekWhenVideoCopyWithSeek() {
        // 视频转封装 + seek：视频停留在关键帧，关闭精确 seek 让（可能重编码的）音频同样从关键帧起步，保持对齐
        TranscodeCommandBuilder.TranscodeRequest request = new TranscodeCommandBuilder.TranscodeRequest(
                1_717_501, null, Path.of("/data/movie.mkv"), null, "h264", "ac3", null, null, false);
        List<String> command = builder.buildCommand(request, TranscodeCommandBuilder.ENCODER_COPY,
                "/dev/dri/renderD128", 0, Path.of("/out/session8"));
        String joined = String.join(" ", command);

        assertTrue(joined.contains("-ss 1717.501 -noaccurate_seek -i"));
        // ac3 不在 copy 白名单，仍转 AAC（起步点由 -noaccurate_seek 保证与视频一致）
        assertTrue(joined.contains("-c:a aac -b:a 128k -ac 2"));
    }

    @Test
    void shouldKeepAudioCopyWhenVideoCopyWithSeek() {
        // 视频转封装 + seek + 可 copy 音频：两条流均不参与精确裁剪，保持 copy 即对齐
        TranscodeCommandBuilder.TranscodeRequest request = new TranscodeCommandBuilder.TranscodeRequest(
                90_500, null, Path.of("/data/movie.mp4"), null, "h264", "aac", null, null, false);
        List<String> command = builder.buildCommand(request, TranscodeCommandBuilder.ENCODER_COPY,
                "/dev/dri/renderD128", 0, Path.of("/out/session9"));
        String joined = String.join(" ", command);

        assertTrue(joined.contains("-c:a copy"));
        assertTrue(joined.contains("-noaccurate_seek"));
    }

    @Test
    void shouldNotTouchAudioCopyOrAccurateSeekWithoutSeek() {
        // 从头播放：无 seek 错位问题，音频照常 copy，也不加 -noaccurate_seek
        List<String> transcode = builder.buildCommand(request("hevc", "aac", 1000L, 480, false),
                "libx264", "/dev/dri/renderD128", 0, Path.of("/out/session10"));
        String transcodeJoined = String.join(" ", transcode);
        assertTrue(transcodeJoined.contains("-c:a copy"));
        assertFalse(transcodeJoined.contains("-noaccurate_seek"));

        List<String> remux = builder.buildCommand(request("h264", "aac", null, null, false),
                TranscodeCommandBuilder.ENCODER_COPY, "/dev/dri/renderD128", 0, Path.of("/out/session11"));
        assertFalse(String.join(" ", remux).contains("-noaccurate_seek"));
    }

    @Test
    void testValidateParams() {
        TranscodeCommandBuilder.validateParams(null, null);
        TranscodeCommandBuilder.validateParams(2000L, 720);
        TranscodeCommandBuilder.validateParams(500L, 360);
        assertThrows(BusinessException.class, () -> TranscodeCommandBuilder.validateParams(0L, null));
        assertThrows(BusinessException.class, () -> TranscodeCommandBuilder.validateParams(-5L, null));
        assertThrows(BusinessException.class, () -> TranscodeCommandBuilder.validateParams(null, 700));
        assertThrows(BusinessException.class, () -> TranscodeCommandBuilder.validateParams(null, 1000));
    }

    @Test
    void testSelectEncoder() {
        // 显式 none / 未配置 → 软解
        assertEquals("libx264", builder.selectEncoder("none"));
        assertEquals("libx264", builder.selectEncoder(""));
        // 显式指定硬解方式直接映射
        assertEquals("h264_vaapi", builder.selectEncoder("vaapi"));
        assertEquals("h264_qsv", builder.selectEncoder("qsv"));
        assertEquals("h264_nvenc", builder.selectEncoder("nvenc"));
        assertEquals("libx264", builder.selectEncoder("unknown"));
    }
}
