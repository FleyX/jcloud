package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaDirectorySourceMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.support.MediaProbeSupport;
import com.fleyx.jcloud.service.support.TranscodeCommandBuilder;
import com.fleyx.jcloud.service.support.TranscodeSessionManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内嵌位图字幕轨烧录的会话创建契约（工单 02）：
 * subtitleIndex 序号校验（文本轨拒绝、不存在拒绝）与位图序号透传到 TranscodeRequest。
 * 探测结果以 mock 提供——真实 ffprobe 无法就地生成 PGS 等位图轨样本，mock 可同时覆盖文本/位图/不存在三态。
 */
@Transactional
class MediaPlaybackBurnInServiceTest extends MediaScanTestBase {

    @MockitoBean
    private MediaProbeSupport mediaProbeSupport;

    @MockitoBean
    private TranscodeSessionManager transcodeSessionManager;

    @MockitoBean
    private MediaScrapeService mediaScrapeService;

    @Autowired
    private MediaPlaybackService mediaPlaybackService;

    @Autowired
    private MediaScanService mediaScanService;

    @Autowired
    private MediaMovieMapper mediaMovieMapper;

    @Autowired
    private MediaDirectoryMapper mediaDirectoryMapper;

    @Autowired
    private MediaDirectorySourceMapper mediaDirectorySourceMapper;

    /**
     * 探测结果：0 号文本轨（subrip）+ 1 号位图轨（hdmv_pgs_subtitle），轨道序号与 ffmpeg 0:s:N 一致。
     */
    private MediaProbeResult probeResultWithBitmapTrack() {
        return new MediaProbeResult(6_000L, "matroska", "h264", "aac", 1280, 720, 1_000_000L,
                List.of(new MediaProbeResult.Track(0, "aac", "chi", null, true)),
                List.of(new MediaProbeResult.Track(0, "subrip", "chi", "简体", true),
                        new MediaProbeResult.Track(1, "hdmv_pgs_subtitle", "jpn", "PGS", false)));
    }

    /**
     * 位图序号透传：subtitleIndex 命中位图轨时放行，会话请求携带该序号（强制转码由命令构建器保证）。
     */
    @Test
    void shouldPassThroughBitmapSubtitleIndex() {
        UserVo user = prepareUserWithStorageSpace().user();
        MediaMovie movie = setupMovie(user);
        when(mediaProbeSupport.probe(any(Path.class))).thenReturn(probeResultWithBitmapTrack());

        mediaPlaybackService.createTranscodeSession(movie.getId(), 0L, null, 1, null, null, false,
                user.getId(), null);

        ArgumentCaptor<TranscodeCommandBuilder.TranscodeRequest> captor =
                ArgumentCaptor.forClass(TranscodeCommandBuilder.TranscodeRequest.class);
        verify(transcodeSessionManager).createSession(eq(user.getId()), captor.capture());
        assertEquals(1, captor.getValue().subtitleIndex());
        assertNotNull(captor.getValue().localPath());
    }

    /**
     * 文本轨序号拒绝：文本字幕走 WebVTT 链路，不接受烧录，返回参数错误且不创建会话。
     */
    @Test
    void shouldRejectTextSubtitleIndex() {
        UserVo user = prepareUserWithStorageSpace().user();
        MediaMovie movie = setupMovie(user);
        when(mediaProbeSupport.probe(any(Path.class))).thenReturn(probeResultWithBitmapTrack());

        BusinessException e = assertThrows(BusinessException.class, () ->
                mediaPlaybackService.createTranscodeSession(movie.getId(), 0L, null, 0, null, null, false,
                        user.getId(), null));
        assertEquals("文本字幕无需烧录", e.getMessage());
        verify(transcodeSessionManager, never()).createSession(any(), any());
    }

    /**
     * 不存在序号拒绝：subtitleIndex 超出字幕轨列表返回参数错误。
     */
    @Test
    void shouldRejectNonexistentSubtitleIndex() {
        UserVo user = prepareUserWithStorageSpace().user();
        MediaMovie movie = setupMovie(user);
        when(mediaProbeSupport.probe(any(Path.class))).thenReturn(probeResultWithBitmapTrack());

        BusinessException e = assertThrows(BusinessException.class, () ->
                mediaPlaybackService.createTranscodeSession(movie.getId(), 0L, null, 2, null, null, false,
                        user.getId(), null));
        assertEquals("字幕轨不存在", e.getMessage());
    }

    /**
     * probe 失败回退为空轨列表：任何烧录序号都命中「字幕轨不存在」，不会带病放行。
     */
    @Test
    void shouldRejectSubtitleIndexWhenProbeFails() {
        UserVo user = prepareUserWithStorageSpace().user();
        MediaMovie movie = setupMovie(user);
        when(mediaProbeSupport.probe(any(Path.class)))
                .thenThrow(new com.fleyx.jcloud.common.exception.SystemException(
                        com.fleyx.jcloud.common.enums.ResultCode.SYSTEM_ERROR, "probe 失败"));

        BusinessException e = assertThrows(BusinessException.class, () ->
                mediaPlaybackService.createTranscodeSession(movie.getId(), 0L, null, 0, null, null, false,
                        user.getId(), null));
        assertEquals("字幕轨不存在", e.getMessage());
    }

    private MediaMovie setupMovie(UserVo user) {
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        upload(user.getId(), dune.getId(), "沙丘.mkv");
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());
        return mediaMovieMapper.selectOne(new LambdaQueryWrapper<MediaMovie>()
                .eq(MediaMovie::getDirectoryId, directory.getId()));
    }

    private MediaDirectory createDirectory(String userId, String folderNodeId, String mediaType) {
        MediaDirectory directory = new MediaDirectory();
        directory.setUserId(userId);
        directory.setName("测试媒体库");
        directory.setMediaType(mediaType);
        mediaDirectoryMapper.insert(directory);
        MediaDirectorySource source = new MediaDirectorySource();
        source.setDirectoryId(directory.getId());
        source.setFileNodeId(folderNodeId);
        mediaDirectorySourceMapper.insert(source);
        return directory;
    }
}
