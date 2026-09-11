package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.config.MediaProperties;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaSubtitleMapper;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.vo.MediaPlaybackInfoVo;
import com.fleyx.jcloud.service.MediaPlaybackService;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.service.SystemStorageSpaceProvider;
import com.fleyx.jcloud.service.support.MediaBurnInSubtitleSupport;
import com.fleyx.jcloud.service.support.MediaFileStreamSupport;
import com.fleyx.jcloud.service.support.MediaPlaybackResolveSupport;
import com.fleyx.jcloud.service.support.MediaProbeSupport;
import com.fleyx.jcloud.service.support.MediaSubtitleSupport;
import com.fleyx.jcloud.service.support.TranscodeSessionManager;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 媒体播放服务实现测试——纯播放模式（by-file-node 变体，工单 02）：
 * 播放信息文件事实全部来自实时探测、进度恒为 0、他人文件不可播、探测失败抛 SystemException、
 * 全链路对媒体数据零写入。
 */
class MediaPlaybackServiceImplTest {

    private final MediaPlaybackResolveSupport mediaPlaybackResolveSupport = mock(MediaPlaybackResolveSupport.class);
    private final FileMapper fileMapper = mock(FileMapper.class);
    private final RemoteFileService remoteFileService = mock(RemoteFileService.class);
    private final MediaProbeSupport mediaProbeSupport = mock(MediaProbeSupport.class);
    private final MediaSubtitleSupport mediaSubtitleSupport = mock(MediaSubtitleSupport.class);
    private final MediaSubtitleMapper mediaSubtitleMapper = mock(MediaSubtitleMapper.class);
    private final MediaBurnInSubtitleSupport mediaBurnInSubtitleSupport = mock(MediaBurnInSubtitleSupport.class);
    private final TranscodeSessionManager transcodeSessionManager = mock(TranscodeSessionManager.class);
    private final MediaFileStreamSupport mediaFileStreamSupport = mock(MediaFileStreamSupport.class);
    private final MediaProperties mediaProperties = mock(MediaProperties.class);
    private final SystemStorageSpaceProvider systemStorageSpaceProvider = mock(SystemStorageSpaceProvider.class);

    private final MediaPlaybackServiceImpl service = new MediaPlaybackServiceImpl(
            mediaPlaybackResolveSupport, fileMapper, remoteFileService, mediaProbeSupport,
            mediaSubtitleSupport, mediaSubtitleMapper, mediaBurnInSubtitleSupport,
            transcodeSessionManager, mediaFileStreamSupport, mediaProperties, systemStorageSpaceProvider);

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
        when(mediaSubtitleSupport.buildSubtitleList(probe.subtitleTracks(), "fn-1")).thenReturn(List.of());

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
        // 字幕列表按纯播放 fileRowId=fileNodeId 装配（查不到外部字幕，只剩内嵌轨）
        verify(mediaSubtitleSupport).buildSubtitleList(probe.subtitleTracks(), "fn-1");
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
     * 零写入：纯播放播放信息 + 直放流全链路执行后，影视归档解析/字幕表/烧录/转码会话/
     * 远程下载均无任何交互（不产出进度、已观看、「继续观看」等媒体数据）。
     */
    @Test
    void shouldNotWriteAnyMediaDataForPurePlay() {
        FileNode node = localNode("fn-1", "user-1");
        when(fileMapper.selectById("fn-1")).thenReturn(node);
        Path localPath = Path.of("/data/user-1/movie.mp4");
        when(mediaFileStreamSupport.resolveLocalPath(node, "user-1")).thenReturn(localPath);
        MediaProbeResult probe = probeResult();
        when(mediaProbeSupport.probe(localPath)).thenReturn(probe);
        when(mediaSubtitleSupport.buildSubtitleList(probe.subtitleTracks(), "fn-1")).thenReturn(List.of());
        FileDownloadResult download = new FileDownloadResult(
                "movie.mp4", new ByteArrayInputStream(new byte[0]), "video/mp4", 1_024L);
        when(mediaFileStreamSupport.streamNode(node, "bytes=0-1", "user-1"))
                .thenReturn(new MediaPlaybackService.MediaStreamResult(download, 0L, 1L, 1_024L, "movie.mp4"));

        service.getPlaybackInfoByFileNode("fn-1", "user-1");
        MediaPlaybackService.MediaStreamResult stream = service.streamByFileNode("fn-1", "bytes=0-1", "user-1");

        assertEquals(0L, stream.rangeStart());
        verify(mediaFileStreamSupport).streamNode(node, "bytes=0-1", "user-1");
        verifyNoInteractions(mediaPlaybackResolveSupport, mediaSubtitleMapper,
                mediaBurnInSubtitleSupport, transcodeSessionManager, remoteFileService);
    }
}
