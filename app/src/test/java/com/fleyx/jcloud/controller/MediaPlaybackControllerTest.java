package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.vo.MediaPlaybackInfoVo;
import com.fleyx.jcloud.service.MediaPlaybackService;
import com.fleyx.jcloud.service.support.PlaybackConfigConstants;
import com.fleyx.jcloud.service.support.TranscodeSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 媒体播放控制器测试（含全局播放配置接口，ADR 0024）。
 */
class MediaPlaybackControllerTest {

    private final MediaPlaybackService mediaPlaybackService = mock(MediaPlaybackService.class);
    private final TranscodeSessionManager transcodeSessionManager = mock(TranscodeSessionManager.class);

    private final MediaPlaybackController controller =
            new MediaPlaybackController(mediaPlaybackService, transcodeSessionManager);

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void setUp() {
        UserContext.set(new CurrentUser("user-1", "user-1"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /**
     * GET /playback-config 返回档位七档（key/label/kbps/maxHeight）、直放/转封装白名单、
     * 看完阈值 0.95，取值与后端常量（重构前前端硬编码）逐一一致。
     */
    @Test
    void shouldReturnPlaybackConfig() throws Exception {
        when(mediaPlaybackService.getPlaybackConfig()).thenReturn(PlaybackConfigConstants.buildConfig());

        mockMvc.perform(get("/jcloud/api/media/playback-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.bitrateTiers.length()").value(7))
                .andExpect(jsonPath("$.data.bitrateTiers[0].key").value("original"))
                .andExpect(jsonPath("$.data.bitrateTiers[0].label").value("原画"))
                .andExpect(jsonPath("$.data.bitrateTiers[0].kbps").value(nullValue()))
                .andExpect(jsonPath("$.data.bitrateTiers[0].maxHeight").value(nullValue()))
                .andExpect(jsonPath("$.data.bitrateTiers[1].key").value("20000-2160"))
                .andExpect(jsonPath("$.data.bitrateTiers[1].label").value("20M · 4K"))
                .andExpect(jsonPath("$.data.bitrateTiers[1].kbps").value(20000))
                .andExpect(jsonPath("$.data.bitrateTiers[1].maxHeight").value(2160))
                .andExpect(jsonPath("$.data.bitrateTiers[2].key").value("8000-1080"))
                .andExpect(jsonPath("$.data.bitrateTiers[2].label").value("8M · 1080p"))
                .andExpect(jsonPath("$.data.bitrateTiers[2].kbps").value(8000))
                .andExpect(jsonPath("$.data.bitrateTiers[2].maxHeight").value(1080))
                .andExpect(jsonPath("$.data.bitrateTiers[3].key").value("4000-1080"))
                .andExpect(jsonPath("$.data.bitrateTiers[3].label").value("4M · 1080p"))
                .andExpect(jsonPath("$.data.bitrateTiers[3].kbps").value(4000))
                .andExpect(jsonPath("$.data.bitrateTiers[3].maxHeight").value(1080))
                .andExpect(jsonPath("$.data.bitrateTiers[4].key").value("2000-720"))
                .andExpect(jsonPath("$.data.bitrateTiers[4].label").value("2M · 720p"))
                .andExpect(jsonPath("$.data.bitrateTiers[4].kbps").value(2000))
                .andExpect(jsonPath("$.data.bitrateTiers[4].maxHeight").value(720))
                .andExpect(jsonPath("$.data.bitrateTiers[5].key").value("1000-480"))
                .andExpect(jsonPath("$.data.bitrateTiers[5].label").value("1M · 480p"))
                .andExpect(jsonPath("$.data.bitrateTiers[5].kbps").value(1000))
                .andExpect(jsonPath("$.data.bitrateTiers[5].maxHeight").value(480))
                .andExpect(jsonPath("$.data.bitrateTiers[6].key").value("500-360"))
                .andExpect(jsonPath("$.data.bitrateTiers[6].label").value("500K · 360p"))
                .andExpect(jsonPath("$.data.bitrateTiers[6].kbps").value(500))
                .andExpect(jsonPath("$.data.bitrateTiers[6].maxHeight").value(360))
                .andExpect(jsonPath("$.data.directPlay.containers",
                        containsInAnyOrder("mp4", "mov", "m4v", "webm")))
                .andExpect(jsonPath("$.data.directPlay.videoCodecs",
                        containsInAnyOrder("h264", "hevc", "vp8", "vp9", "av1")))
                .andExpect(jsonPath("$.data.directPlay.audioCodecs",
                        containsInAnyOrder("aac", "mp3", "opus", "vorbis", "flac")))
                .andExpect(jsonPath("$.data.remux.videoCopyCodecs",
                        containsInAnyOrder("h264", "hevc", "vp9", "av1")))
                .andExpect(jsonPath("$.data.remux.audioCopyCodecs",
                        containsInAnyOrder("aac", "mp3")))
                .andExpect(jsonPath("$.data.finishedRatio").value(0.95));
    }

    /**
     * 纯播放播放信息端点：200、字段透传、service 参数为文件节点 ID + 当前用户。
     */
    @Test
    void shouldReturnPlaybackInfoByFileNode() throws Exception {
        MediaPlaybackInfoVo vo = new MediaPlaybackInfoVo();
        vo.setMode("direct");
        vo.setDirectUrl("/jcloud/api/media/files/fn-1/stream");
        vo.setDurationMs(3_600_000L);
        vo.setProgressMs(0L);
        vo.setFileName("movie.mp4");
        when(mediaPlaybackService.getPlaybackInfoByFileNode("fn-1", "user-1")).thenReturn(vo);

        mockMvc.perform(get("/jcloud/api/media/files/fn-1/playback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.mode").value("direct"))
                .andExpect(jsonPath("$.data.directUrl").value("/jcloud/api/media/files/fn-1/stream"))
                .andExpect(jsonPath("$.data.fileName").value("movie.mp4"))
                .andExpect(jsonPath("$.data.progressMs").value(0))
                .andExpect(jsonPath("$.data.versionId").value(nullValue()));

        verify(mediaPlaybackService).getPlaybackInfoByFileNode("fn-1", "user-1");
    }

    /**
     * 纯播放播放信息端点：service 抛 BusinessException(404) 时透传到响应体 code。
     */
    @Test
    void shouldPassthroughNotFoundForPlaybackInfoByFileNode() throws Exception {
        when(mediaPlaybackService.getPlaybackInfoByFileNode("fn-9", "user-1"))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "文件不存在"));

        mockMvc.perform(get("/jcloud/api/media/files/fn-9/playback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("文件不存在"));
    }

    /**
     * 纯播放直放流端点：Range 请求装配 206/Content-Range/Accept-Ranges/inline 文件名，
     * service 参数为文件节点 ID + Range 头 + 当前用户。
     */
    @Test
    void shouldReturnPartialContentForStreamByFileNode() throws Exception {
        FileDownloadResult download = new FileDownloadResult(
                "movie.mp4", new ByteArrayInputStream(new byte[100]), "video/mp4", 100L);
        MediaPlaybackService.MediaStreamResult result =
                new MediaPlaybackService.MediaStreamResult(download, 0L, 99L, 100L, "movie.mp4");
        when(mediaPlaybackService.streamByFileNode("fn-1", "bytes=0-99", "user-1")).thenReturn(result);

        mockMvc.perform(get("/jcloud/api/media/files/fn-1/stream").header("Range", "bytes=0-99"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-99/100"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Disposition", "inline; filename*=UTF-8''movie.mp4"))
                .andExpect(header().longValue("Content-Length", 100));

        verify(mediaPlaybackService).streamByFileNode("fn-1", "bytes=0-99", "user-1");
    }

    /**
     * 纯播放直放流端点：无 Range 时整段 200 返回；service 抛 BusinessException(404) 透传响应体 code。
     */
    @Test
    void shouldReturnOkStreamAndPassthroughNotFound() throws Exception {
        FileDownloadResult download = new FileDownloadResult(
                "movie.mp4", new ByteArrayInputStream(new byte[100]), "video/mp4", 100L);
        MediaPlaybackService.MediaStreamResult result =
                new MediaPlaybackService.MediaStreamResult(download, null, null, 100L, "movie.mp4");
        when(mediaPlaybackService.streamByFileNode("fn-1", null, "user-1")).thenReturn(result);

        mockMvc.perform(get("/jcloud/api/media/files/fn-1/stream"))
                .andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", 100));

        when(mediaPlaybackService.streamByFileNode("fn-9", null, "user-1"))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "文件不存在"));
        mockMvc.perform(get("/jcloud/api/media/files/fn-9/stream"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }
}
