package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.service.MediaDirectoryService;
import com.fleyx.jcloud.service.MediaHomeService;
import com.fleyx.jcloud.service.MediaItemService;
import com.fleyx.jcloud.service.MediaPlaybackService;
import com.fleyx.jcloud.service.MediaScanService;
import com.fleyx.jcloud.service.MediaScrapeService;
import com.fleyx.jcloud.service.SystemStorageSpaceProvider;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.TranscodeSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 媒体库控制器测试。
 */
class MediaControllerTest {

    private final MediaDirectoryService mediaDirectoryService = mock(MediaDirectoryService.class);
    private final MediaScanService mediaScanService = mock(MediaScanService.class);
    private final MediaScrapeService mediaScrapeService = mock(MediaScrapeService.class);
    private final MediaItemService mediaItemService = mock(MediaItemService.class);
    private final MediaHomeService mediaHomeService = mock(MediaHomeService.class);
    private final MediaPlaybackService mediaPlaybackService = mock(MediaPlaybackService.class);
    private final TmdbService tmdbService = mock(TmdbService.class);
    private final MediaMetadataMapper mediaMetadataMapper = mock(MediaMetadataMapper.class);
    private final SystemStorageSpaceProvider systemStorageSpaceProvider = mock(SystemStorageSpaceProvider.class);
    private final TranscodeSessionManager transcodeSessionManager = mock(TranscodeSessionManager.class);

    private final MediaController mediaController = new MediaController(
            mediaDirectoryService, mediaScanService, mediaScrapeService, mediaItemService,
            mediaHomeService, mediaPlaybackService, tmdbService, mediaMetadataMapper,
            systemStorageSpaceProvider, transcodeSessionManager);

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(mediaController)
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
     * 按文件节点 ID 反查媒体条目 ID：命中时返回条目 ID。
     */
    @Test
    void shouldReturnItemIdByFileNodeId() throws Exception {
        when(mediaItemService.getItemIdByFileNodeId(eq("fn-1"), eq("user-1"))).thenReturn("item-1");

        mockMvc.perform(get("/jcloud/api/media/items/by-file-node/fn-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("item-1"));
    }

    /**
     * 按文件节点 ID 反查媒体条目 ID：未命中时透传业务异常。
     */
    @Test
    void shouldReturnNotFoundWhenItemMissing() throws Exception {
        when(mediaItemService.getItemIdByFileNodeId(eq("fn-2"), eq("user-1")))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在"));

        mockMvc.perform(get("/jcloud/api/media/items/by-file-node/fn-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("媒体条目不存在"));
    }
}
