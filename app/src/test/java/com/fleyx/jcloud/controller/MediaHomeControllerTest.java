package com.fleyx.jcloud.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.MediaFavoriteOwnerType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.model.dto.MediaFavoriteQueryDto;
import com.fleyx.jcloud.model.vo.MediaFavoriteVo;
import com.fleyx.jcloud.model.vo.MediaHomeVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaSearchResultVo;
import com.fleyx.jcloud.service.MediaFavoriteService;
import com.fleyx.jcloud.service.MediaHomeService;
import com.fleyx.jcloud.service.MediaItemService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * 影视首页、搜索与收藏控制器单元测试（路由、DTO 透传与 R 包装）。
 */
class MediaHomeControllerTest {

    private final MediaHomeService mediaHomeService = mock(MediaHomeService.class);
    private final MediaItemService mediaItemService = mock(MediaItemService.class);
    private final MediaFavoriteService mediaFavoriteService = mock(MediaFavoriteService.class);

    private final MediaHomeController controller =
            new MediaHomeController(mediaHomeService, mediaItemService, mediaFavoriteService);

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
     * GET /home：首页聚合透传当前用户，返回 R.ok 包装的聚合视图。
     */
    @Test
    void shouldReturnHomeAggregation() throws Exception {
        MediaHomeVo vo = new MediaHomeVo();
        MediaItemVo item = new MediaItemVo();
        item.setId("m-1");
        vo.setLatestMovies(List.of(item));
        when(mediaHomeService.getHome("user-1")).thenReturn(vo);

        mockMvc.perform(get("/jcloud/api/media/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.latestMovies[0].id").value("m-1"));

        verify(mediaHomeService).getHome(eq("user-1"));
    }

    /**
     * GET /search：关键词去空格后透传，size 缺省取 8，返回 R.ok 包装的分组搜索结果。
     */
    @Test
    void shouldSearchWithDefaultSize() throws Exception {
        MediaSearchResultVo result = new MediaSearchResultVo();
        MediaItemVo movie = new MediaItemVo();
        movie.setId("m-1");
        Page<MediaItemVo> movies = new Page<>(1, 8);
        movies.setRecords(List.of(movie));
        result.setMovies(movies);
        when(mediaItemService.search("user-1", "复仇", 8)).thenReturn(result);

        mockMvc.perform(get("/jcloud/api/media/search").param("keyword", "  复仇  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.movies.records[0].id").value("m-1"));

        verify(mediaItemService).search(eq("user-1"), eq("复仇"), eq(8));
    }

    /**
     * GET /search：显式传 size 时透传到搜索服务。
     */
    @Test
    void shouldSearchWithExplicitSize() throws Exception {
        MediaSearchResultVo result = new MediaSearchResultVo();
        when(mediaItemService.search("user-1", "西部", 5)).thenReturn(result);

        mockMvc.perform(get("/jcloud/api/media/search")
                        .param("keyword", "西部")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(mediaItemService).search(eq("user-1"), eq("西部"), eq(5));
    }

    /**
     * GET /search 缺 keyword：缺少必填请求参数，GlobalExceptionHandler 包装为 R（body code=400）。
     */
    @Test
    void shouldRejectSearchWithoutKeyword() throws Exception {
        mockMvc.perform(get("/jcloud/api/media/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("缺少请求参数：keyword")));

        verify(mediaItemService, never()).search(any(), any(), anyInt());
    }

    /**
     * POST /favorites/toggle：收藏切换 DTO 透传，返回 R.ok 包装的切换后状态。
     */
    @Test
    void shouldToggleFavorite() throws Exception {
        when(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.MOVIE, "movie-1")).thenReturn(true);

        mockMvc.perform(post("/jcloud/api/media/favorites/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerType\":\"movie\",\"ownerId\":\"movie-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        verify(mediaFavoriteService).toggle(eq("user-1"), eq(MediaFavoriteOwnerType.MOVIE), eq("movie-1"));
    }

    /**
     * POST /favorites/toggle 缺 ownerId：@Valid 校验失败，包装为 R（body code=400），不触发收藏服务。
     */
    @Test
    void shouldRejectToggleWithoutOwnerId() throws Exception {
        mockMvc.perform(post("/jcloud/api/media/favorites/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerType\":\"movie\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("收藏实体 ID 不能为空")));

        verify(mediaFavoriteService, never()).toggle(any(), any(), any());
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

    /**
     * GET /favorites 缺 ownerType：@Valid 校验失败（BindException），包装为 R（body code=400）。
     */
    @Test
    void shouldRejectFavoritesWithoutOwnerType() throws Exception {
        mockMvc.perform(get("/jcloud/api/media/favorites"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("收藏类型不能为空")));

        verify(mediaFavoriteService, never()).pageFavorites(any(), any());
    }
}
