package com.fleyx.jcloud.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.MediaRefreshMode;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.model.dto.MediaFavoriteQueryDto;
import com.fleyx.jcloud.model.dto.MediaMatchUpdateDto;
import com.fleyx.jcloud.model.vo.MediaFavoriteVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.service.MediaDirectoryService;
import com.fleyx.jcloud.service.MediaFavoriteService;
import com.fleyx.jcloud.service.MediaHomeService;
import com.fleyx.jcloud.service.MediaItemService;
import com.fleyx.jcloud.service.MediaPlaybackService;
import com.fleyx.jcloud.service.MediaScanService;
import com.fleyx.jcloud.service.MediaScrapeService;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.MediaArtworkPersistSupport;
import com.fleyx.jcloud.service.support.TranscodeSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 媒体库控制器测试。
 */
class MediaControllerTest {

    private final MediaDirectoryService mediaDirectoryService = mock(MediaDirectoryService.class);
    private final MediaFavoriteService mediaFavoriteService = mock(MediaFavoriteService.class);
    private final MediaScanService mediaScanService = mock(MediaScanService.class);
    private final MediaScrapeService mediaScrapeService = mock(MediaScrapeService.class);
    private final MediaItemService mediaItemService = mock(MediaItemService.class);
    private final MediaHomeService mediaHomeService = mock(MediaHomeService.class);
    private final MediaPlaybackService mediaPlaybackService = mock(MediaPlaybackService.class);
    private final TmdbService tmdbService = mock(TmdbService.class);
    private final MediaMetadataMapper mediaMetadataMapper = mock(MediaMetadataMapper.class);
    private final FileMapper fileMapper = mock(FileMapper.class);
    private final MediaArtworkPersistSupport mediaArtworkPersistSupport = mock(MediaArtworkPersistSupport.class);
    private final TranscodeSessionManager transcodeSessionManager = mock(TranscodeSessionManager.class);

    private final MediaItemController mediaItemController = new MediaItemController(mediaItemService);
    private final MediaMetadataController mediaMetadataController = new MediaMetadataController(
            mediaMetadataMapper, fileMapper, mediaArtworkPersistSupport, mediaScrapeService, tmdbService);
    private final MediaHomeController mediaHomeController = new MediaHomeController(
            mediaHomeService, mediaItemService, mediaFavoriteService);

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            mediaItemController, mediaMetadataController, mediaHomeController)
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
     * 手动修正统一按行 ID（issue #21）：PUT /items/{id}/match 转发电影/剧集行 ID。
     */
    @Test
    void shouldUpdateMatchByRowId() throws Exception {
        MediaItemVo vo = new MediaItemVo();
        vo.setId("movie-1");
        when(mediaItemService.updateMatch(eq("movie-1"), any(MediaMatchUpdateDto.class), eq("user-1")))
                .thenReturn(vo);

        mockMvc.perform(put("/jcloud/api/media/items/movie-1/match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tmdbId\":100,\"mediaType\":\"movie\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("movie-1"));
    }

    /**
     * 刷新元数据（工单 06 两模式）：mode 缺省 = missing，转发当前用户与模式到削刮服务。
     */
    @Test
    void shouldRefreshOwnMetadata() throws Exception {
        mockMvc.perform(post("/jcloud/api/media/metadata/meta-1/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(mediaScrapeService).refreshItem("meta-1", "user-1", MediaRefreshMode.MISSING);
    }

    /**
     * 刷新元数据：显式传 force 模式时透传到削刮服务。
     */
    @Test
    void shouldRefreshWithForceMode() throws Exception {
        mockMvc.perform(post("/jcloud/api/media/metadata/meta-1/refresh").param("mode", "force"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(mediaScrapeService).refreshItem("meta-1", "user-1", MediaRefreshMode.FORCE);
    }

    /**
     * 刷新元数据：非法 mode 值抛参数错误（工单 08 枚举化），不触发削刮服务。
     */
    @Test
    void shouldRejectInvalidRefreshMode() throws Exception {
        mockMvc.perform(post("/jcloud/api/media/metadata/meta-1/refresh").param("mode", "bogus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));

        verify(mediaScrapeService, never()).refreshItem(any(), any(), any());
    }

    /**
     * 刷新元数据：削刮服务按归属/存在性拒绝（元数据属于其他用户）时异常透传，不触发任何刷新逻辑。
     */
    @Test
    void shouldRejectRefreshOfOtherUsersMetadata() throws Exception {
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "元数据不存在"))
                .when(mediaScrapeService).refreshItem("meta-2", "user-1", MediaRefreshMode.MISSING);

        mockMvc.perform(post("/jcloud/api/media/metadata/meta-2/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("元数据不存在"));
    }

    /**
     * 刷新元数据：元数据不存在时削刮服务抛业务异常，异常透传不触发刷新。
     */
    @Test
    void shouldRejectRefreshOfMissingMetadata() throws Exception {
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "元数据不存在"))
                .when(mediaScrapeService).refreshItem("meta-3", "user-1", MediaRefreshMode.MISSING);

        mockMvc.perform(post("/jcloud/api/media/metadata/meta-3/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("元数据不存在"));
    }

    /**
     * 我的收藏分页：ownerType 以小写编码（movie/season…）作为 query 参数绑定，
     * 转发当前用户与分页/库过滤入参。
     */
    @Test
    void shouldPageFavoritesByOwnerType() throws Exception {
        MediaFavoriteVo vo = new MediaFavoriteVo();
        vo.setOwnerType("movie");
        vo.setOwnerId("movie-1");
        Page<MediaFavoriteVo> page = new Page<>(1, 24, 1);
        page.setRecords(List.of(vo));
        when(mediaFavoriteService.pageFavorites(eq("user-1"), any(MediaFavoriteQueryDto.class))).thenReturn(page);

        mockMvc.perform(get("/jcloud/api/media/favorites")
                        .param("ownerType", "movie")
                        .param("directoryId", "dir-1")
                        .param("pageNum", "1")
                        .param("pageSize", "24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].ownerType").value("movie"))
                .andExpect(jsonPath("$.data.records[0].ownerId").value("movie-1"));

        verify(mediaFavoriteService).pageFavorites(eq("user-1"), any(MediaFavoriteQueryDto.class));
    }
}
