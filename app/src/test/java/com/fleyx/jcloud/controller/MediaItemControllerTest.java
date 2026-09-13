package com.fleyx.jcloud.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.model.dto.MediaMatchUpdateDto;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.dto.MediaProgressUpdateDto;
import com.fleyx.jcloud.model.vo.MediaGenreVo;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemLookupVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesVo;
import com.fleyx.jcloud.service.MediaItemService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 媒体条目查询与匹配控制器单元测试（路由、DTO 透传与 R 包装）。
 */
class MediaItemControllerTest {

    private final MediaItemService mediaItemService = mock(MediaItemService.class);

    private final MediaItemController controller = new MediaItemController(mediaItemService);

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
     * 按文件节点 ID 反查媒体条目：命中时返回条目 ID 与版本明细行 ID。
     */
    @Test
    void shouldReturnItemIdByFileNodeId() throws Exception {
        MediaItemLookupVo vo = new MediaItemLookupVo();
        vo.setItemId("item-1");
        vo.setVersionId("version-1");
        when(mediaItemService.lookupByFileNode(eq("fn-1"), eq("user-1"))).thenReturn(vo);

        mockMvc.perform(get("/jcloud/api/media/items/by-file-node/fn-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.itemId").value("item-1"))
                .andExpect(jsonPath("$.data.versionId").value("version-1"));
    }

    /**
     * 按文件节点 ID 反查媒体条目：未命中时透传业务异常。
     */
    @Test
    void shouldReturnNotFoundWhenItemMissing() throws Exception {
        when(mediaItemService.lookupByFileNode(eq("fn-2"), eq("user-1")))
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
     * GET /libraries/{id}/genres：聚合类型列表透传当前用户与媒体库 ID，返回 R.ok 包装的列表。
     */
    @Test
    void shouldListGenresByLibrary() throws Exception {
        MediaGenreVo vo = new MediaGenreVo();
        vo.setName("科幻");
        vo.setItemCount(12L);
        when(mediaItemService.listGenres("user-1", "lib-1")).thenReturn(List.of(vo));

        mockMvc.perform(get("/jcloud/api/media/libraries/lib-1/genres"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].name").value("科幻"))
                .andExpect(jsonPath("$.data[0].itemCount").value(12));

        verify(mediaItemService).listGenres(eq("user-1"), eq("lib-1"));
    }

    /**
     * GET /items/movies：分页/搜索/排序条件绑定到 MediaPageQueryDto 并透传，返回 R.ok 包装的分页数据。
     */
    @Test
    void shouldPageMoviesWithQueryParams() throws Exception {
        MediaItemVo vo = new MediaItemVo();
        vo.setId("m-1");
        Page<MediaItemVo> page = new Page<>(2, 30);
        page.setRecords(List.of(vo));
        when(mediaItemService.listMovies(eq("user-1"), any(MediaPageQueryDto.class))).thenReturn(page);

        mockMvc.perform(get("/jcloud/api/media/items/movies")
                        .param("keyword", "复仇")
                        .param("genre", "科幻")
                        .param("directoryId", "lib-1")
                        .param("pageNum", "2")
                        .param("pageSize", "30")
                        .param("sortField", "rating")
                        .param("sortOrder", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].id").value("m-1"));

        ArgumentCaptor<MediaPageQueryDto> captor = ArgumentCaptor.forClass(MediaPageQueryDto.class);
        verify(mediaItemService).listMovies(eq("user-1"), captor.capture());
        assertEquals("复仇", captor.getValue().getKeyword());
        assertEquals("科幻", captor.getValue().getGenre());
        assertEquals("lib-1", captor.getValue().getDirectoryId());
        assertEquals(2L, captor.getValue().getPageNum());
        assertEquals(30L, captor.getValue().getPageSize());
        assertEquals("rating", captor.getValue().getSortField());
        assertEquals("asc", captor.getValue().getSortOrder());
    }

    /**
     * GET /items/series：分页查询电视剧列表，透传当前用户与查询入参，返回 R.ok 包装的分页数据。
     */
    @Test
    void shouldPageSeriesWithQueryParams() throws Exception {
        MediaSeriesVo vo = new MediaSeriesVo();
        vo.setId("s-1");
        vo.setSeriesName("西部世界");
        Page<MediaSeriesVo> page = new Page<>(1, 48);
        page.setRecords(List.of(vo));
        when(mediaItemService.listSeries(eq("user-1"), any(MediaPageQueryDto.class))).thenReturn(page);

        mockMvc.perform(get("/jcloud/api/media/items/series")
                        .param("keyword", "西部")
                        .param("pageNum", "1")
                        .param("pageSize", "48"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].id").value("s-1"))
                .andExpect(jsonPath("$.data.records[0].seriesName").value("西部世界"));

        verify(mediaItemService).listSeries(eq("user-1"), any(MediaPageQueryDto.class));
    }

    /**
     * GET /items/series/{seriesId}/episodes：剧集列表透传剧 ID 与当前用户，返回 R.ok 包装的列表。
     */
    @Test
    void shouldListEpisodesOfSeries() throws Exception {
        MediaItemVo vo = new MediaItemVo();
        vo.setId("ep-1");
        vo.setItemType("episode");
        vo.setEpisodeNo(2);
        when(mediaItemService.listEpisodes("s-1", "user-1")).thenReturn(List.of(vo));

        mockMvc.perform(get("/jcloud/api/media/items/series/s-1/episodes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value("ep-1"))
                .andExpect(jsonPath("$.data[0].episodeNo").value(2));

        verify(mediaItemService).listEpisodes(eq("s-1"), eq("user-1"));
    }

    /**
     * GET /items/others：分页查询其他类型条目，透传当前用户与查询入参，返回 R.ok 包装的分页数据。
     */
    @Test
    void shouldPageOthersWithQueryParams() throws Exception {
        MediaItemVo vo = new MediaItemVo();
        vo.setId("o-1");
        vo.setItemType("other");
        Page<MediaItemVo> page = new Page<>(1, 48);
        page.setRecords(List.of(vo));
        when(mediaItemService.listOthers(eq("user-1"), any(MediaPageQueryDto.class))).thenReturn(page);

        mockMvc.perform(get("/jcloud/api/media/items/others")
                        .param("pageNum", "1")
                        .param("pageSize", "48"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].id").value("o-1"));

        verify(mediaItemService).listOthers(eq("user-1"), any(MediaPageQueryDto.class));
    }

    /**
     * GET /items/{id}/detail：条目详情透传条目 ID 与当前用户，返回 R.ok 包装的详情视图。
     */
    @Test
    void shouldReturnItemDetail() throws Exception {
        MediaItemDetailVo vo = new MediaItemDetailVo();
        vo.setId("i-1");
        vo.setTitle("星际穿越");
        vo.setItemType("movie");
        when(mediaItemService.getItemDetail("i-1", "user-1")).thenReturn(vo);

        mockMvc.perform(get("/jcloud/api/media/items/i-1/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("i-1"))
                .andExpect(jsonPath("$.data.title").value("星际穿越"));

        verify(mediaItemService).getItemDetail(eq("i-1"), eq("user-1"));
    }

    /**
     * GET /items/{id}/detail 条目不存在：service 抛业务异常，包装为 R（body code=404）。
     */
    @Test
    void shouldWrapBusinessExceptionOnItemDetail() throws Exception {
        when(mediaItemService.getItemDetail("i-404", "user-1"))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在"));

        mockMvc.perform(get("/jcloud/api/media/items/i-404/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("媒体条目不存在"));

        verify(mediaItemService).getItemDetail(eq("i-404"), eq("user-1"));
    }

    /**
     * GET /series/{id}/detail：电视剧详情透传剧 ID 与当前用户，返回 R.ok 包装的详情视图。
     */
    @Test
    void shouldReturnSeriesDetail() throws Exception {
        MediaSeriesDetailVo vo = new MediaSeriesDetailVo();
        vo.setSeriesName("西部世界");
        vo.setTitle("西部世界");
        vo.setSeasonCount(3);
        when(mediaItemService.getSeriesDetail("s-1", "user-1")).thenReturn(vo);

        mockMvc.perform(get("/jcloud/api/media/series/s-1/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value("西部世界"))
                .andExpect(jsonPath("$.data.seasonCount").value(3));

        verify(mediaItemService).getSeriesDetail(eq("s-1"), eq("user-1"));
    }

    /**
     * GET /series/{id}/seasons/{seasonId}/episodes：指定季的剧集列表透传剧/季 ID 与当前用户。
     */
    @Test
    void shouldListSeasonEpisodes() throws Exception {
        MediaItemVo vo = new MediaItemVo();
        vo.setId("ep-2");
        vo.setItemType("episode");
        vo.setSeasonNo(1);
        when(mediaItemService.listSeasonEpisodes("s-1", "se-1", "user-1")).thenReturn(List.of(vo));

        mockMvc.perform(get("/jcloud/api/media/series/s-1/seasons/se-1/episodes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value("ep-2"))
                .andExpect(jsonPath("$.data[0].seasonNo").value(1));

        verify(mediaItemService).listSeasonEpisodes(eq("s-1"), eq("se-1"), eq("user-1"));
    }

    /**
     * PUT /items/{id}/progress：进度 DTO 透传（含版本 ID），返回 R.ok 空数据。
     */
    @Test
    void shouldUpdateProgressAndReturnOk() throws Exception {
        mockMvc.perform(put("/jcloud/api/media/items/i-1/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"progressMs\":5000,\"versionId\":\"v-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        ArgumentCaptor<MediaProgressUpdateDto> captor = ArgumentCaptor.forClass(MediaProgressUpdateDto.class);
        verify(mediaItemService).updateProgress(eq("i-1"), captor.capture(), eq("user-1"));
        assertEquals(5000L, captor.getValue().getProgressMs());
        assertEquals("v-1", captor.getValue().getVersionId());
    }

    /**
     * PUT /items/{id}/match 缺 tmdbId：@Valid 校验失败经 GlobalExceptionHandler 包装为 R（body code=400）。
     */
    @Test
    void shouldRejectMatchWithoutTmdbId() throws Exception {
        mockMvc.perform(put("/jcloud/api/media/items/i-1/match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaType\":\"movie\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("TMDB ID 不能为空")));

        verify(mediaItemService, never()).updateMatch(any(), any(), any());
    }

    /**
     * PUT /items/{id}/match mediaType 非法：@Valid 模式校验失败，包装为 R（body code=400）。
     */
    @Test
    void shouldRejectMatchWithInvalidMediaType() throws Exception {
        mockMvc.perform(put("/jcloud/api/media/items/i-1/match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tmdbId\":100,\"mediaType\":\"anime\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("类型仅支持 movie/tv")));

        verify(mediaItemService, never()).updateMatch(any(), any(), any());
    }

    /**
     * PUT /items/{id}/progress 进度为负：@Valid 校验失败，包装为 R（body code=400）。
     */
    @Test
    void shouldRejectNegativeProgress() throws Exception {
        mockMvc.perform(put("/jcloud/api/media/items/i-1/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"progressMs\":-1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("进度不能为负数")));

        verify(mediaItemService, never()).updateProgress(any(), any(), any());
    }
}
