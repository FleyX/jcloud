package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.config.MediaProperties;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaSubtitle;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.service.SystemStorageSpaceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 字幕转换缓存支撑组件测试：vtt 原样返回、srt/ass 经 ffmpeg 转换缓存（缓存命中短路）、
 * 远程字幕落地缓存、内容版本命名（hash 优先/特殊字符安全化）、时间轴偏移生成与异常路径。
 * ffmpeg 调用以临时脚本替身（写固定 VTT 到末位参数 / 非零退出），不依赖真实 ffmpeg。
 */
class MediaSubtitleConvertSupportTest {

    private final MediaProperties mediaProperties = mock(MediaProperties.class);
    private final RemoteFileService remoteFileService = mock(RemoteFileService.class);
    private final SystemStorageSpaceProvider systemStorageSpaceProvider = mock(SystemStorageSpaceProvider.class);

    private final MediaSubtitleConvertSupport support =
            new MediaSubtitleConvertSupport(mediaProperties, remoteFileService, systemStorageSpaceProvider);

    @TempDir
    Path tempDir;

    private FileNode subtitleNode(String id, String sourceType) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setUserId("user-1");
        node.setName("movie.chs.srt");
        node.setSourceType(sourceType);
        node.setHash("h1");
        return node;
    }

    private MediaSubtitle subtitle(String format) {
        MediaSubtitle subtitle = new MediaSubtitle();
        subtitle.setFileId("row-1");
        subtitle.setFileNodeId("sub-1");
        subtitle.setFormat(format);
        return subtitle;
    }

    /**
     * 系统空间桩：缓存目录 = tempDir/system/media/subtitles。
     */
    private void stubSystemSpace() {
        StorageSpace space = new StorageSpace();
        space.setPath(tempDir.toString());
        when(systemStorageSpaceProvider.getSystemSpace()).thenReturn(space);
    }

    /**
     * 写 ffmpeg 替身脚本：把固定 VTT 内容写到命令末位参数（输出路径），exit 0。
     */
    private Path fakeFfmpegSuccess() throws Exception {
        Path script = tempDir.resolve("fake-ffmpeg.sh");
        Files.writeString(script, """
                #!/bin/sh
                for last; do :; done
                printf 'WEBVTT\\n' > "$last"
                """);
        script.toFile().setExecutable(true);
        when(mediaProperties.getFfmpegPath()).thenReturn(script.toString());
        return script;
    }

    /**
     * 本地 vtt 原样返回字节：不触碰系统空间、远程下载与 ffmpeg。
     */
    @Test
    void shouldReturnLocalVttAsIs() {
        FileNode node = subtitleNode("sub-1", FileNodeConstants.SOURCE_LOCAL);
        Path localPath = tempDir.resolve("movie.chs.vtt");

        Path result = support.resolveExternalVtt(subtitle("vtt"), node, localPath, "user-1");

        assertEquals(localPath, result);
        verifyNoInteractions(systemStorageSpaceProvider, remoteFileService, mediaProperties);
    }

    /**
     * 本地 srt 缓存命中：直接返回既有缓存文件，不触碰 ffmpeg（内容版本取节点 hash）。
     */
    @Test
    void shouldReturnCachedVttOnCacheHit() throws Exception {
        stubSystemSpace();
        FileNode node = subtitleNode("sub-1", FileNodeConstants.SOURCE_LOCAL);
        Path cached = tempDir.resolve("system/media/subtitles/ext_sub-1_h1.vtt");
        Files.createDirectories(cached.getParent());
        Files.writeString(cached, "WEBVTT");

        Path result = support.resolveExternalVtt(subtitle("srt"), node,
                tempDir.resolve("movie.chs.srt"), "user-1");

        assertEquals(cached, result);
        verifyNoInteractions(mediaProperties);
    }

    /**
     * 本地 srt 缓存未命中：经 ffmpeg 替身转换并写入缓存目标，返回缓存路径。
     */
    @Test
    void shouldConvertLocalSrtToCachedVtt() throws Exception {
        stubSystemSpace();
        fakeFfmpegSuccess();
        FileNode node = subtitleNode("sub-1", FileNodeConstants.SOURCE_LOCAL);
        Path localPath = tempDir.resolve("movie.chs.srt");
        Files.writeString(localPath, "1\n00:00:01,000 --> 00:00:02,000\n中文字幕\n");

        Path result = support.resolveExternalVtt(subtitle("srt"), node, localPath, "user-1");

        Path expected = tempDir.resolve("system/media/subtitles/ext_sub-1_h1.vtt");
        assertEquals(expected, result);
        assertEquals("WEBVTT\n", Files.readString(result));
    }

    /**
     * 远程 vtt：落地系统缓存后返回（远程字幕不直接暴露本地路径）。
     */
    @Test
    void shouldCopyRemoteVttToSystemCache() throws Exception {
        stubSystemSpace();
        FileNode node = subtitleNode("sub-9", FileNodeConstants.SOURCE_REMOTE);
        node.setName("movie.chs.vtt");
        when(remoteFileService.download(node, "user-1"))
                .thenReturn(new com.fleyx.jcloud.model.bo.FileDownloadResult(
                        "movie.chs.vtt", new ByteArrayInputStream("WEBVTT\n".getBytes()),
                        "text/vtt", 7L));

        Path result = support.resolveExternalVtt(subtitle("vtt"), node, null, "user-1");

        Path expected = tempDir.resolve("system/media/subtitles/ext_sub-9_h1.vtt");
        assertEquals(expected, result);
        assertEquals("WEBVTT\n", Files.readString(result));
    }

    /**
     * ffmpeg 非零退出：抛业务异常「字幕转换失败」，不静默产出坏缓存。
     */
    @Test
    void shouldRejectWhenFfmpegExitsNonZero() throws Exception {
        stubSystemSpace();
        Path script = tempDir.resolve("fake-ffmpeg-fail.sh");
        Files.writeString(script, "#!/bin/sh\nexit 1\n");
        script.toFile().setExecutable(true);
        when(mediaProperties.getFfmpegPath()).thenReturn(script.toString());
        FileNode node = subtitleNode("sub-1", FileNodeConstants.SOURCE_LOCAL);
        Path localPath = tempDir.resolve("movie.chs.srt");
        Files.writeString(localPath, "not-a-real-subtitle");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> support.resolveExternalVtt(subtitle("srt"), node, localPath, "user-1"));

        assertEquals(ResultCode.BUSINESS_ERROR, exception.getResultCode());
        assertEquals("字幕转换失败，该字幕格式可能不受支持", exception.getMessage());
    }

    /**
     * 内容版本命名：hash 缺失时回退文件大小 + 最后修改时间。
     */
    @Test
    void shouldFallbackContentVersionToSizeAndMtime() throws Exception {
        stubSystemSpace();
        fakeFfmpegSuccess();
        FileNode node = subtitleNode("sub-2", FileNodeConstants.SOURCE_LOCAL);
        node.setHash(null);
        node.setSize(100L);
        node.setLastModified(1_700_000_000_000L);
        Path localPath = tempDir.resolve("movie.chs.srt");
        Files.writeString(localPath, "x");

        Path result = support.resolveExternalVtt(subtitle("srt"), node, localPath, "user-1");

        assertEquals(tempDir.resolve("system/media/subtitles/ext_sub-2_size100_mtime1700000000000.vtt"),
                result);
    }

    /**
     * 时间轴偏移：从规范 VTT 生成独立偏移文件（不改写规范缓存），cue 时间整体减去会话起点，
     * 结束时间不晚于偏移量的过期 cue 完全移除；偏移结果已存在时直接复用。
     */
    @Test
    void shouldWriteOffsetVttAndReuseExisting() throws Exception {
        stubSystemSpace();
        FileNode node = subtitleNode("sub-1", FileNodeConstants.SOURCE_LOCAL);
        Path canonical = tempDir.resolve("ext_sub-1_h1.vtt");
        Files.writeString(canonical,
                "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nexpired\n\n00:00:31.000 --> 00:00:32.000\nhello\n");

        Path result = support.resolveOffsetVtt(node, canonical, 30_000L);

        Path expected = tempDir.resolve("system/media/subtitles/ext_sub-1_h1_off30000.vtt");
        assertEquals(expected, result);
        String offset = Files.readString(result);
        // 会话起点 30s：31s 的 cue 偏移到 1s；1s 的过期 cue 被移除
        assertTrue(offset.contains("00:00:01.000 --> 00:00:02.000\nhello"));
        assertFalse(offset.contains("expired"));
        // 规范缓存不被改写
        assertEquals("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nexpired\n\n00:00:31.000 --> 00:00:32.000\nhello\n",
                Files.readString(canonical));
        // 幂等：已存在直接返回
        assertEquals(expected, support.resolveOffsetVtt(node, canonical, 30_000L));
    }

    /**
     * 规范 VTT 缺失（读取抛 IOException）：包装为 SystemException「字幕时间偏移失败」。
     */
    @Test
    void shouldWrapMissingCanonicalAsSystemException() {
        stubSystemSpace();
        FileNode node = subtitleNode("sub-1", FileNodeConstants.SOURCE_LOCAL);
        Path missing = tempDir.resolve("not-exist.vtt");

        SystemException exception = assertThrows(SystemException.class,
                () -> support.resolveOffsetVtt(node, missing, 30_000L));

        assertEquals("字幕时间偏移失败", exception.getMessage());
    }
}
