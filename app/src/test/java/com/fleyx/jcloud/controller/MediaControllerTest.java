package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaMetadataV2Mapper;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.service.MediaDirectoryService;
import com.fleyx.jcloud.service.MediaHomeService;
import com.fleyx.jcloud.service.MediaItemService;
import com.fleyx.jcloud.service.MediaPlaybackService;
import com.fleyx.jcloud.service.MediaScanService;
import com.fleyx.jcloud.service.MediaScrapeService;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.MediaArtworkPersistSupport;
import com.fleyx.jcloud.service.support.MediaMetadataCompleteSupport;
import com.fleyx.jcloud.service.support.TranscodeSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private final MediaMetadataV2Mapper mediaMetadataV2Mapper = mock(MediaMetadataV2Mapper.class);
    private final FileMapper fileMapper = mock(FileMapper.class);
    private final MediaArtworkPersistSupport mediaArtworkPersistSupport = mock(MediaArtworkPersistSupport.class);
    private final MediaMetadataCompleteSupport metadataCompleteSupport = mock(MediaMetadataCompleteSupport.class);
    private final TranscodeSessionManager transcodeSessionManager = mock(TranscodeSessionManager.class);

    private final MediaController mediaController = new MediaController(
            mediaDirectoryService, mediaScanService, mediaScrapeService, mediaItemService,
            mediaHomeService, mediaPlaybackService, tmdbService, mediaMetadataMapper,
            mediaMetadataV2Mapper, fileMapper, mediaArtworkPersistSupport,
            metadataCompleteSupport, transcodeSessionManager);

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

    /**
     * 刷新元数据：元数据属于当前登录用户时放行并调用 TMDB 刷新。
     */
    @Test
    void shouldRefreshOwnMetadata() throws Exception {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setId("meta-1");
        metadata.setUserId("user-1");
        when(mediaMetadataMapper.selectById("meta-1")).thenReturn(metadata);

        mockMvc.perform(post("/jcloud/api/media/metadata/meta-1/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(tmdbService).refresh("meta-1");
    }

    /**
     * 刷新元数据：元数据属于其他用户时按业务拦截，不触发刷新。
     */
    @Test
    void shouldRejectRefreshOfOtherUsersMetadata() throws Exception {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setId("meta-2");
        metadata.setUserId("user-2");
        when(mediaMetadataMapper.selectById("meta-2")).thenReturn(metadata);

        mockMvc.perform(post("/jcloud/api/media/metadata/meta-2/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("元数据不存在"));

        verify(tmdbService, never()).refresh(anyString());
    }

    /**
     * 刷新元数据：元数据不存在时按业务拦截，不触发刷新。
     */
    @Test
    void shouldRejectRefreshOfMissingMetadata() throws Exception {
        when(mediaMetadataMapper.selectById("meta-3")).thenReturn(null);

        mockMvc.perform(post("/jcloud/api/media/metadata/meta-3/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("元数据不存在"));

        verify(tmdbService, never()).refresh(anyString());
    }
}
