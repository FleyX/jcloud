package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.model.vo.MediaPlaybackConfigVo;
import com.fleyx.jcloud.service.MediaPlaybackService;
import com.fleyx.jcloud.service.support.TranscodeSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        when(mediaPlaybackService.getPlaybackConfig()).thenReturn(buildConfig());

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

    private MediaPlaybackConfigVo buildConfig() {
        return new MediaPlaybackConfigVo(List.of(
                new MediaPlaybackConfigVo.BitrateTierVo("original", "原画", null, null),
                new MediaPlaybackConfigVo.BitrateTierVo("20000-2160", "20M · 4K", 20000, 2160),
                new MediaPlaybackConfigVo.BitrateTierVo("8000-1080", "8M · 1080p", 8000, 1080),
                new MediaPlaybackConfigVo.BitrateTierVo("4000-1080", "4M · 1080p", 4000, 1080),
                new MediaPlaybackConfigVo.BitrateTierVo("2000-720", "2M · 720p", 2000, 720),
                new MediaPlaybackConfigVo.BitrateTierVo("1000-480", "1M · 480p", 1000, 480),
                new MediaPlaybackConfigVo.BitrateTierVo("500-360", "500K · 360p", 500, 360)),
                new MediaPlaybackConfigVo.DirectPlayVo(
                        Set.of("mp4", "mov", "m4v", "webm"),
                        Set.of("h264", "hevc", "vp8", "vp9", "av1"),
                        Set.of("aac", "mp3", "opus", "vorbis", "flac")),
                new MediaPlaybackConfigVo.RemuxVo(Set.of("h264", "hevc", "vp9", "av1"), Set.of("aac", "mp3")),
                0.95);
    }
}
