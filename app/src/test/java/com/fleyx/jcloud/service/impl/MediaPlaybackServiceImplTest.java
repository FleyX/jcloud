package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.config.MediaProperties;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaSubtitleMapper;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.bo.TranscodeSessionParams;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaSubtitle;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.vo.MediaPlaybackInfoVo;
import com.fleyx.jcloud.service.MediaPlaybackService;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.service.SystemStorageSpaceProvider;
import com.fleyx.jcloud.service.support.MediaBurnInSubtitleSupport;
import com.fleyx.jcloud.service.support.MediaFileStreamSupport;
import com.fleyx.jcloud.service.support.MediaPlaybackResolveSupport;
import com.fleyx.jcloud.service.support.MediaProbeSupport;
import com.fleyx.jcloud.service.support.MediaSubtitleConvertSupport;
import com.fleyx.jcloud.service.support.MediaSubtitleSupport;
import com.fleyx.jcloud.service.support.TranscodeCommandBuilder;
import com.fleyx.jcloud.service.support.TranscodeSession;
import com.fleyx.jcloud.service.support.TranscodeSessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 媒体播放服务实现测试——纯播放模式（by-file-node 变体，工单 02/03/04）：
 * 播放信息文件事实全部来自实时探测、进度恒为 0、他人文件不可播、探测失败抛 SystemException、
 * 全链路对媒体数据零写入；工单 03 起纯播放转码会话创建落地；工单 04 起字幕全集落地
 * （内嵌/外挂字幕 by-file-node 端点、外挂字幕实时探测归属校验、外挂位图烧录节点 ID 装配）。
 */
class MediaPlaybackServiceImplTest {

    private final MediaPlaybackResolveSupport mediaPlaybackResolveSupport = mock(MediaPlaybackResolveSupport.class);
    private final FileMapper fileMapper = mock(FileMapper.class);
    private final RemoteFileService remoteFileService = mock(RemoteFileService.class);
    private final MediaProbeSupport mediaProbeSupport = mock(MediaProbeSupport.class);
    private final MediaSubtitleSupport mediaSubtitleSupport = mock(MediaSubtitleSupport.class);
    private final MediaSubtitleConvertSupport mediaSubtitleConvertSupport = mock(MediaSubtitleConvertSupport.class);
    private final MediaSubtitleMapper mediaSubtitleMapper = mock(MediaSubtitleMapper.class);
    private final MediaBurnInSubtitleSupport mediaBurnInSubtitleSupport = mock(MediaBurnInSubtitleSupport.class);
    private final TranscodeSessionManager transcodeSessionManager = mock(TranscodeSessionManager.class);
    private final MediaFileStreamSupport mediaFileStreamSupport = mock(MediaFileStreamSupport.class);
    private final MediaProperties mediaProperties = mock(MediaProperties.class);
    private final SystemStorageSpaceProvider systemStorageSpaceProvider = mock(SystemStorageSpaceProvider.class);

    private final MediaPlaybackServiceImpl service = new MediaPlaybackServiceImpl(
            mediaPlaybackResolveSupport, fileMapper, remoteFileService, mediaProbeSupport,
            mediaSubtitleSupport, mediaSubtitleConvertSupport, mediaSubtitleMapper, mediaBurnInSubtitleSupport,
            transcodeSessionManager, mediaFileStreamSupport, mediaProperties, systemStorageSpaceProvider);

    @TempDir
    Path tempDir;

    private FileNode localNode(String id, String userId) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setUserId(userId);
        node.setName("movie.mp4");
        node.setSize(1_024L);
        node.setSourceType("local");
        return node;
    }

    private MediaProbeResult probeResult() {
        return new MediaProbeResult(3_600_000L, "mp4", "h264", "aac", 1920, 1080, 5_000_000L,
                List.of(new MediaProbeResult.Track(0, "aac", "ja", null, true)),
                List.of(new MediaProbeResult.Track(0, "subrip", "zh", null, false)));
    }

    /**
     * 纯播放正常路径：VO 文件事实全部来自 probe 结果，progressMs=0、versionId=null、
     * fileName 为节点名，直放判定命中白名单时 mode=direct 且 directUrl 为 files 形态。
     */
    @Test
    void shouldBuildPurePlaybackInfoFromProbe() {
        FileNode node = localNode("fn-1", "user-1");
        when(fileMapper.selectById("fn-1")).thenReturn(node);
        Path localPath = Path.of("/data/user-1/movie.mp4");
        when(mediaFileStreamSupport.resolveLocalPath(node, "user-1")).thenReturn(localPath);
        MediaProbeResult probe = probeResult();
        when(mediaProbeSupport.probe(localPath)).thenReturn(probe);
        when(mediaSubtitleSupport.buildPureSubtitleList(probe.subtitleTracks(), node)).thenReturn(List.of());

        MediaPlaybackInfoVo vo = service.getPlaybackInfoByFileNode("fn-1", "user-1");

        assertEquals("direct", vo.getMode());
        assertEquals("/jcloud/api/media/files/fn-1/stream", vo.getDirectUrl());
        assertEquals(3_600_000L, vo.getDurationMs());
        assertEquals("mp4", vo.getContainer());
        assertEquals("h264", vo.getVideoCodec());
        assertEquals("aac", vo.getAudioCodec());
        assertEquals(1920, vo.getWidth());
        assertEquals(1080, vo.getHeight());
        assertEquals(5_000_000L, vo.getEffectiveBitRate());
        assertEquals(0L, vo.getProgressMs());
        assertNull(vo.getVersionId());
        assertEquals("movie.mp4", vo.getFileName());
        // 字幕列表按纯播放语义装配：内嵌轨 + 实时探测外挂（传入视频节点）
        verify(mediaSubtitleSupport).buildPureSubtitleList(probe.subtitleTracks(), node);
        // 纯播放不触碰影视归档解析
        verifyNoInteractions(mediaPlaybackResolveSupport);
    }

    /**
     * 纯播放 strict 探测：probe 抛异常时无存档字段可兜底，抛 SystemException 并带原异常。
     */
    @Test
    void shouldThrowSystemExceptionWhenProbeFailed() {
        FileNode node = localNode("fn-1", "user-1");
        when(fileMapper.selectById("fn-1")).thenReturn(node);
        Path localPath = Path.of("/data/user-1/movie.mp4");
        when(mediaFileStreamSupport.resolveLocalPath(node, "user-1")).thenReturn(localPath);
        RuntimeException cause = new RuntimeException("ffprobe exit 1");
        when(mediaProbeSupport.probe(localPath)).thenThrow(cause);

        SystemException exception = assertThrows(SystemException.class,
                () -> service.getPlaybackInfoByFileNode("fn-1", "user-1"));

        assertEquals("视频文件探测失败", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    /**
     * 纯播放直放流：文件节点归属他人时抛 NOT_FOUND，他人文件不可播。
     */
    @Test
    void shouldRejectStreamByFileNodeOwnedByOthers() {
        when(fileMapper.selectById("fn-1")).thenReturn(localNode("fn-1", "other-user"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.streamByFileNode("fn-1", "bytes=0-1", "user-1"));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
    }

    /**
     * 零写入：纯播放全链路（播放信息 + 直放流 + 转码会话 + 内嵌/外挂字幕端点）执行后，
     * 影视归档解析、字幕关联表、远程下载等写能力协作者零交互，且各协作者仅发生预期的只读调用——
     * 任何遗漏的写调用（播放进度/已观看/「继续观看」等媒体数据）都会让本测试变红。
     */
    @Test
    void shouldNotWriteAnyMediaDataForPurePlay() throws Exception {
        FileNode node = localNode("fn-1", "user-1");
        FileNode subNode = localNode("sub-1", "user-1");
        subNode.setName("movie.chs.srt");
        when(fileMapper.selectById("fn-1")).thenReturn(node);
        when(fileMapper.selectById("sub-1")).thenReturn(subNode);
        Path localPath = Path.of("/data/user-1/movie.mp4");
        Path subLocalPath = Path.of("/data/user-1/movie.chs.srt");
        when(mediaFileStreamSupport.resolveLocalPath(node, "user-1")).thenReturn(localPath);
        when(mediaFileStreamSupport.resolveLocalPath(subNode, "user-1")).thenReturn(subLocalPath);
        MediaProbeResult probe = probeResult();
        when(mediaProbeSupport.probe(localPath)).thenReturn(probe);
        when(mediaSubtitleSupport.buildPureSubtitleList(probe.subtitleTracks(), node)).thenReturn(List.of());
        MediaSubtitle detected = new MediaSubtitle();
        detected.setFileId("fn-1");
        detected.setFileNodeId("sub-1");
        detected.setFormat("srt");
        when(mediaSubtitleSupport.findDetectedSubtitle(node, "sub-1")).thenReturn(detected);
        Path vtt = Path.of("/cache/ext_sub-1.vtt");
        when(mediaSubtitleConvertSupport.resolveExternalVtt(detected, subNode, subLocalPath, "user-1"))
                .thenReturn(vtt);
        FileDownloadResult download = new FileDownloadResult(
                "movie.mp4", new ByteArrayInputStream(new byte[0]), "video/mp4", 1_024L);
        when(mediaFileStreamSupport.streamNode(node, "bytes=0-1", "user-1"))
                .thenReturn(new MediaPlaybackService.MediaStreamResult(download, 0L, 1L, 1_024L, "movie.mp4"));
        when(mediaBurnInSubtitleSupport.resolveExternalSubtitleBurnByFileNode(any(), any(), any(), any()))
                .thenReturn(new MediaBurnInSubtitleSupport.ExternalSubtitleBurn(null, null));
        when(transcodeSessionManager.createSession(any(), any()))
                .thenReturn(new TranscodeSession("s-1", "user-1", null, null, "copy", Instant.now()));
        StorageSpace space = new StorageSpace();
        space.setPath(tempDir.toString());
        when(systemStorageSpaceProvider.getSystemSpace()).thenReturn(space);
        // 内嵌字幕缓存命中（canonical 已存在，不触发 ffmpeg 提取）
        Path canonical = tempDir.resolve("system/media/subtitles/fn-1_0.vtt");
        Files.createDirectories(canonical.getParent());
        Files.writeString(canonical, "WEBVTT");

        service.getPlaybackInfoByFileNode("fn-1", "user-1");
        MediaPlaybackService.MediaStreamResult stream = service.streamByFileNode("fn-1", "bytes=0-1", "user-1");
        service.createTranscodeSessionByFileNode("fn-1", "user-1",
                new TranscodeSessionParams(0L, null, null, null, null, null, false));
        service.extractSubtitleByFileNode("fn-1", 0, 0, "user-1");
        service.extractExternalSubtitleByFileNode("fn-1", "sub-1", 0, "user-1");

        assertEquals(0L, stream.rangeStart());
        // 零交互：影视归档解析/字幕关联表/远程下载/ffmpeg 配置等写能力协作者全程不触碰
        verifyNoInteractions(mediaPlaybackResolveSupport, mediaSubtitleMapper, remoteFileService, mediaProperties);
        // 已发生的交互逐条钉死后，任何多余调用（含一切写调用）都会让本测试变红
        verify(fileMapper, times(5)).selectById("fn-1");
        verify(fileMapper).selectById("sub-1");
        verifyNoMoreInteractions(fileMapper);
        // 播放信息探测 + 转码会话探测/装配各解析一次视频本地路径，外挂字幕收尾再解析一次字幕路径
        verify(mediaFileStreamSupport, times(3)).resolveLocalPath(node, "user-1");
        verify(mediaFileStreamSupport).resolveLocalPath(subNode, "user-1");
        verify(mediaFileStreamSupport).streamNode(node, "bytes=0-1", "user-1");
        verifyNoMoreInteractions(mediaFileStreamSupport);
        verify(mediaProbeSupport, times(2)).probe(localPath);
        verifyNoMoreInteractions(mediaProbeSupport);
        verify(mediaSubtitleSupport).buildPureSubtitleList(probe.subtitleTracks(), node);
        verify(mediaSubtitleSupport).findDetectedSubtitle(node, "sub-1");
        verifyNoMoreInteractions(mediaSubtitleSupport);
        verify(mediaSubtitleConvertSupport).resolveExternalVtt(detected, subNode, subLocalPath, "user-1");
        verifyNoMoreInteractions(mediaSubtitleConvertSupport);
        verify(mediaBurnInSubtitleSupport).resolveExternalSubtitleBurnByFileNode(
                eq(node), isNull(), eq("user-1"), any());
        verify(mediaBurnInSubtitleSupport).validateSubtitleIndex(probe, null);
        verifyNoMoreInteractions(mediaBurnInSubtitleSupport);
        verify(transcodeSessionManager).createSession(eq("user-1"), any());
        verifyNoMoreInteractions(transcodeSessionManager);
        verify(systemStorageSpaceProvider).getSystemSpace();
        verifyNoMoreInteractions(systemStorageSpaceProvider);
    }

    /**
     * 纯播放转码会话正常创建（本地文件）：TranscodeRequest 的 videoCodec/audioCodec 全部来自 probe
     * （playable 兜底字段全 null）、audioIndex 命中 probe 对应轨 codec、本地装 localPath、档位参数透传。
     */
    @Test
    void shouldCreatePureTranscodeSessionFromProbe() {
        FileNode node = localNode("fn-1", "user-1");
        when(fileMapper.selectById("fn-1")).thenReturn(node);
        Path localPath = Path.of("/data/user-1/movie.mkv");
        when(mediaFileStreamSupport.resolveLocalPath(node, "user-1")).thenReturn(localPath);
        MediaProbeResult probe = new MediaProbeResult(3_600_000L, "mkv", "hevc", "aac", 1920, 1080, 5_000_000L,
                List.of(new MediaProbeResult.Track(0, "aac", "ja", null, true),
                        new MediaProbeResult.Track(1, "ac3", "en", null, false)),
                List.of());
        when(mediaProbeSupport.probe(localPath)).thenReturn(probe);
        when(mediaBurnInSubtitleSupport.resolveExternalSubtitleBurnByFileNode(any(), any(), any(), any()))
                .thenReturn(new MediaBurnInSubtitleSupport.ExternalSubtitleBurn(null, null));
        when(transcodeSessionManager.createSession(any(), any()))
                .thenReturn(new TranscodeSession("s-1", "user-1", null, null, "copy", Instant.now()));

        TranscodeSession session = service.createTranscodeSessionByFileNode("fn-1", "user-1",
                new TranscodeSessionParams(30_000L, 1, null, null, 2_000L, 720, true));

        assertEquals("s-1", session.id());
        ArgumentCaptor<TranscodeCommandBuilder.TranscodeRequest> captor =
                ArgumentCaptor.forClass(TranscodeCommandBuilder.TranscodeRequest.class);
        verify(transcodeSessionManager).createSession(any(), captor.capture());
        TranscodeCommandBuilder.TranscodeRequest request = captor.getValue();
        assertEquals(30_000L, request.startMs());
        assertEquals(1, request.audioIndex());
        assertEquals("hevc", request.videoCodec());
        // audioIndex=1 命中 probe 第二条音轨，codec 取该轨而非容器默认音轨
        assertEquals("ac3", request.audioCodec());
        assertEquals(localPath, request.localPath());
        assertNull(request.remoteStream());
        assertEquals(2_000L, request.targetBitrateKbps());
        assertEquals(720, request.maxHeight());
        assertTrue(request.forceVideoTranscode());
        assertNull(request.subtitleIndex());
        assertNull(request.externalSubtitlePath());
        assertNull(request.externalSubtitleStream());
    }

    /**
     * 纯播放转码会话正常创建（远程文件）：remoteStream supplier 装配、localPath 为 null、
     * 编码同样全部取自 probe（probe 走下载流）。
     */
    @Test
    void shouldCreatePureTranscodeSessionWithRemoteStream() {
        FileNode node = localNode("fn-1", "user-1");
        node.setSourceType("remote");
        when(fileMapper.selectById("fn-1")).thenReturn(node);
        MediaProbeResult probe = new MediaProbeResult(3_600_000L, "mkv", "hevc", "aac", 1920, 1080, 5_000_000L,
                List.of(new MediaProbeResult.Track(0, "aac", "ja", null, true)), List.of());
        when(remoteFileService.download(node, "user-1"))
                .thenReturn(new FileDownloadResult("movie.mkv", new ByteArrayInputStream(new byte[0]),
                        "video/x-matroska", 1_024L));
        when(mediaProbeSupport.probe(any(InputStream.class))).thenReturn(probe);
        when(mediaBurnInSubtitleSupport.resolveExternalSubtitleBurnByFileNode(any(), any(), any(), any()))
                .thenReturn(new MediaBurnInSubtitleSupport.ExternalSubtitleBurn(null, null));
        when(transcodeSessionManager.createSession(any(), any()))
                .thenReturn(new TranscodeSession("s-1", "user-1", null, null, "copy", Instant.now()));

        service.createTranscodeSessionByFileNode("fn-1", "user-1",
                new TranscodeSessionParams(0L, null, null, null, null, null, false));

        ArgumentCaptor<TranscodeCommandBuilder.TranscodeRequest> captor =
                ArgumentCaptor.forClass(TranscodeCommandBuilder.TranscodeRequest.class);
        verify(transcodeSessionManager).createSession(any(), captor.capture());
        TranscodeCommandBuilder.TranscodeRequest request = captor.getValue();
        assertNull(request.localPath());
        assertNotNull(request.remoteStream());
        assertEquals("hevc", request.videoCodec());
        assertEquals("aac", request.audioCodec());
        // supplier 真实取流：验证远程下载装配（探测一次 + supplier 一次）
        assertNotNull(request.remoteStream().get());
        verify(remoteFileService, times(2)).download(node, "user-1");
    }

    /**
     * 纯播放转码会话：文件节点归属他人时抛 NOT_FOUND（探测与会话创建之前）。
     */
    @Test
    void shouldRejectPureTranscodeSessionOwnedByOthers() {
        when(fileMapper.selectById("fn-1")).thenReturn(localNode("fn-1", "other-user"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createTranscodeSessionByFileNode("fn-1", "user-1",
                        new TranscodeSessionParams(0L, null, null, null, null, null, false)));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
        verifyNoInteractions(mediaProbeSupport, transcodeSessionManager);
    }

    /**
     * 非法 maxHeight 走既有 validateParams 报 PARAM_ERROR，不触碰文件查询与会话创建。
     */
    @Test
    void shouldRejectInvalidMaxHeightForPureTranscodeSession() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createTranscodeSessionByFileNode("fn-1", "user-1",
                        new TranscodeSessionParams(0L, null, null, null, null, 999, false)));

        assertEquals(ResultCode.PARAM_ERROR, exception.getResultCode());
        verifyNoInteractions(fileMapper, mediaProbeSupport, transcodeSessionManager);
    }

    /**
     * 内嵌位图轨与外挂字幕同传拒绝（与已收录链路同一校验）。
     */
    @Test
    void shouldRejectSubtitleIndexAndExternalTogetherForPureTranscodeSession() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createTranscodeSessionByFileNode("fn-1", "user-1",
                        new TranscodeSessionParams(0L, null, 2, "sub-1", null, null, false)));

        assertEquals(ResultCode.PARAM_ERROR, exception.getResultCode());
        assertEquals("内嵌与外部字幕只能二选一", exception.getMessage());
        verifyNoInteractions(fileMapper, mediaProbeSupport, transcodeSessionManager);
    }

    /**
     * 纯播放播放信息不可直放（容器不在直放白名单）时 mode=transcode，
     * transcodeUrl 为 files 形态（/jcloud/api/media/files/{fileNodeId}/transcode）。
     */
    @Test
    void shouldExposeFilesTranscodeUrlForPurePlaybackInfo() {
        FileNode node = localNode("fn-1", "user-1");
        when(fileMapper.selectById("fn-1")).thenReturn(node);
        Path localPath = Path.of("/data/user-1/movie.mp4");
        when(mediaFileStreamSupport.resolveLocalPath(node, "user-1")).thenReturn(localPath);
        // mkv 容器不在直放白名单 → 转码
        MediaProbeResult probe = new MediaProbeResult(3_600_000L, "mkv", "h264", "aac", 1920, 1080, 5_000_000L,
                List.of(), List.of());
        when(mediaProbeSupport.probe(localPath)).thenReturn(probe);
        when(mediaSubtitleSupport.buildPureSubtitleList(probe.subtitleTracks(), node)).thenReturn(List.of());

        MediaPlaybackInfoVo vo = service.getPlaybackInfoByFileNode("fn-1", "user-1");

        assertEquals("transcode", vo.getMode());
        assertEquals("/jcloud/api/media/files/fn-1/transcode", vo.getTranscodeUrl());
        assertNull(vo.getDirectUrl());
    }

    /**
     * 纯播放内嵌字幕提取（工单 04）：缓存 fileNodeId_index.vtt 命中直接返回（不触碰 ffmpeg），
     * offsetMs>0 时经 convertSupport 生成独立偏移结果。
     */
    @Test
    void shouldExtractPureEmbeddedSubtitleViaCacheAndOffset() throws Exception {
        FileNode node = localNode("fn-1", "user-1");
        when(fileMapper.selectById("fn-1")).thenReturn(node);
        StorageSpace space = new StorageSpace();
        space.setPath(tempDir.toString());
        when(systemStorageSpaceProvider.getSystemSpace()).thenReturn(space);
        Path canonical = tempDir.resolve("system/media/subtitles/fn-1_0.vtt");
        Files.createDirectories(canonical.getParent());
        Files.writeString(canonical, "WEBVTT");

        assertEquals(canonical, service.extractSubtitleByFileNode("fn-1", 0, 0, "user-1"));

        Path offsetVtt = tempDir.resolve("system/media/subtitles/fn-1_0_off30000.vtt");
        when(mediaSubtitleConvertSupport.resolveOffsetVtt(canonical, "fn-1_0", 30_000L)).thenReturn(offsetVtt);
        assertEquals(offsetVtt, service.extractSubtitleByFileNode("fn-1", 0, 30_000, "user-1"));
        verify(mediaSubtitleConvertSupport).resolveOffsetVtt(canonical, "fn-1_0", 30_000L);
        // 缓存命中：不触碰影视归档解析与字幕关联表
        verifyNoInteractions(mediaPlaybackResolveSupport, mediaSubtitleMapper);
    }

    /**
     * 纯播放两个字幕端点拒绝负偏移（校验先于文件查询）。
     */
    @Test
    void shouldRejectNegativeOffsetForPureSubtitleEndpoints() {
        assertThrows(BusinessException.class, () -> service.extractSubtitleByFileNode("fn-1", 0, -1, "user-1"));
        assertThrows(BusinessException.class,
                () -> service.extractExternalSubtitleByFileNode("fn-1", "sub-1", -1, "user-1"));
        verifyNoInteractions(fileMapper);
    }

    /**
     * 纯播放外挂字幕读取（工单 04）：视频与字幕节点归属校验后，经共用归属校验在实时探测命中集合内定位，
     * 命中后经 convertSupport 转 VTT；offsetMs>0 走独立偏移结果。
     */
    @Test
    void shouldExtractPureExternalSubtitleFromDetectedSet() {
        FileNode video = localNode("fn-1", "user-1");
        FileNode subNode = localNode("sub-1", "user-1");
        subNode.setName("movie.chs.srt");
        when(fileMapper.selectById("fn-1")).thenReturn(video);
        when(fileMapper.selectById("sub-1")).thenReturn(subNode);
        MediaSubtitle detected = new MediaSubtitle();
        detected.setFileId("fn-1");
        detected.setFileNodeId("sub-1");
        detected.setFormat("srt");
        when(mediaSubtitleSupport.findDetectedSubtitle(video, "sub-1")).thenReturn(detected);
        Path localPath = Path.of("/data/user-1/movie.chs.srt");
        when(mediaFileStreamSupport.resolveLocalPath(subNode, "user-1")).thenReturn(localPath);
        Path vtt = Path.of("/cache/ext_sub-1.vtt");
        when(mediaSubtitleConvertSupport.resolveExternalVtt(detected, subNode, localPath, "user-1")).thenReturn(vtt);

        assertEquals(vtt, service.extractExternalSubtitleByFileNode("fn-1", "sub-1", 0, "user-1"));

        Path offsetVtt = Path.of("/cache/ext_sub-1_off60000.vtt");
        when(mediaSubtitleConvertSupport.resolveOffsetVtt(subNode, vtt, 60_000L)).thenReturn(offsetVtt);
        assertEquals(offsetVtt, service.extractExternalSubtitleByFileNode("fn-1", "sub-1", 60_000, "user-1"));
        verify(mediaSubtitleConvertSupport).resolveOffsetVtt(subNode, vtt, 60_000L);
        // 纯播放全路径不读/写字幕关联表
        verifyNoInteractions(mediaSubtitleMapper);
    }

    /**
     * 纯播放外挂字幕读取：共用归属校验未命中（实时探测空集合或命中其他节点）抛 404「字幕不存在」；
     * 字幕文件节点归属他人在探测之前即 404。
     */
    @Test
    void shouldRejectPureExternalSubtitleNotDetectedOrOwnedByOthers() {
        FileNode video = localNode("fn-1", "user-1");
        when(fileMapper.selectById("fn-1")).thenReturn(video);
        when(fileMapper.selectById("sub-1")).thenReturn(localNode("sub-1", "user-1"));
        // 探测未命中（空集合或命中其他节点）→ 共用归属校验抛 404
        when(mediaSubtitleSupport.findDetectedSubtitle(video, "sub-1"))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "字幕不存在"));
        BusinessException missed = assertThrows(BusinessException.class,
                () -> service.extractExternalSubtitleByFileNode("fn-1", "sub-1", 0, "user-1"));
        assertEquals(ResultCode.NOT_FOUND, missed.getResultCode());
        assertEquals("字幕不存在", missed.getMessage());

        // 字幕文件节点归属他人 → 404（探测之前即拦截）
        when(fileMapper.selectById("sub-1")).thenReturn(localNode("sub-1", "other-user"));
        BusinessException owned = assertThrows(BusinessException.class,
                () -> service.extractExternalSubtitleByFileNode("fn-1", "sub-1", 0, "user-1"));
        assertEquals(ResultCode.NOT_FOUND, owned.getResultCode());
        // 归属拦截不再触发探测：全链路仅一次归属校验
        verify(mediaSubtitleSupport).findDetectedSubtitle(video, "sub-1");
    }

    /**
     * 纯播放外挂位图烧录（工单 04）：externalSubtitleId（字幕文件节点 ID）经 by-file-node 装配，
     * 装出的本地外挂路径进入 TranscodeRequest；已收录链路的记录装配不被触碰。
     */
    @Test
    void shouldAssemblePureExternalBurnBySubtitleFileNodeId() {
        FileNode node = localNode("fn-1", "user-1");
        when(fileMapper.selectById("fn-1")).thenReturn(node);
        Path videoPath = Path.of("/data/user-1/movie.mkv");
        when(mediaFileStreamSupport.resolveLocalPath(node, "user-1")).thenReturn(videoPath);
        when(mediaProbeSupport.probe(videoPath)).thenReturn(probeResult());
        Path subPath = Path.of("/data/user-1/movie.sup");
        when(mediaBurnInSubtitleSupport.resolveExternalSubtitleBurnByFileNode(eq(node), eq("sub-1"), eq("user-1"), any()))
                .thenReturn(new MediaBurnInSubtitleSupport.ExternalSubtitleBurn(subPath, null));
        when(transcodeSessionManager.createSession(any(), any()))
                .thenReturn(new TranscodeSession("s-1", "user-1", null, null, "copy", Instant.now()));

        service.createTranscodeSessionByFileNode("fn-1", "user-1",
                new TranscodeSessionParams(0L, null, null, "sub-1", null, null, false));

        ArgumentCaptor<TranscodeCommandBuilder.TranscodeRequest> captor =
                ArgumentCaptor.forClass(TranscodeCommandBuilder.TranscodeRequest.class);
        verify(transcodeSessionManager).createSession(any(), captor.capture());
        assertEquals(subPath, captor.getValue().externalSubtitlePath());
        assertNull(captor.getValue().externalSubtitleStream());
        verify(mediaBurnInSubtitleSupport, never()).resolveExternalSubtitleBurn(any(), any(), any(), any());
        // 纯播放全路径不读/写字幕关联表
        verifyNoInteractions(mediaSubtitleMapper);
    }
}
