package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.MediaRefreshMode;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.vo.TmdbSearchResultVo;
import com.fleyx.jcloud.service.MediaScrapeService;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.MediaArtworkPersistSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 媒体元数据图片与刷新控制器单元测试（路由、R 包装与异常透传）。
 */
class MediaMetadataControllerTest {

    private final MediaMetadataMapper mediaMetadataMapper = mock(MediaMetadataMapper.class);
    private final FileMapper fileMapper = mock(FileMapper.class);
    private final MediaArtworkPersistSupport mediaArtworkPersistSupport = mock(MediaArtworkPersistSupport.class);
    private final MediaScrapeService mediaScrapeService = mock(MediaScrapeService.class);
    private final TmdbService tmdbService = mock(TmdbService.class);

    private final MediaMetadataController controller = new MediaMetadataController(
            mediaMetadataMapper, fileMapper, mediaArtworkPersistSupport, mediaScrapeService, tmdbService);

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
     * GET /metadata/{id}/poster：命中且属于当前用户时按图片文件节点直读，返回图片字节与缓存头。
     */
    @Test
    void shouldReturnPosterImage() throws Exception {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setUserId("user-1");
        metadata.setPosterFileNodeId("f-1");
        when(mediaMetadataMapper.selectById("meta-1")).thenReturn(metadata);
        FileNode node = new FileNode();
        node.setId("f-1");
        when(fileMapper.selectById("f-1")).thenReturn(node);
        when(mediaArtworkPersistSupport.readFileBytes(node)).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(get("/jcloud/api/media/metadata/meta-1/poster"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=3600"))
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    /**
     * GET /metadata/{id}/poster 元数据不存在：抛业务异常，包装为 R（body code=404）。
     */
    @Test
    void shouldRejectPosterOfMissingMetadata() throws Exception {
        when(mediaMetadataMapper.selectById("meta-x")).thenReturn(null);

        mockMvc.perform(get("/jcloud/api/media/metadata/meta-x/poster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("海报不存在"));
    }

    /**
     * GET /metadata/{id}/poster 元数据属于其他用户：归属校验失败，包装为 R（body code=404）。
     */
    @Test
    void shouldRejectPosterOfOtherUser() throws Exception {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setUserId("other-user");
        metadata.setPosterFileNodeId("f-1");
        when(mediaMetadataMapper.selectById("meta-2")).thenReturn(metadata);

        mockMvc.perform(get("/jcloud/api/media/metadata/meta-2/poster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("海报不存在"));
    }

    /**
     * GET /metadata/{id}/backdrop：命中且属于当前用户时按背景图文件节点直读，返回图片字节与缓存头。
     */
    @Test
    void shouldReturnBackdropImage() throws Exception {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setUserId("user-1");
        metadata.setBackdropFileNodeId("f-2");
        when(mediaMetadataMapper.selectById("meta-1")).thenReturn(metadata);
        FileNode node = new FileNode();
        node.setId("f-2");
        when(fileMapper.selectById("f-2")).thenReturn(node);
        when(mediaArtworkPersistSupport.readFileBytes(node)).thenReturn(new byte[]{4, 5, 6});

        mockMvc.perform(get("/jcloud/api/media/metadata/meta-1/backdrop"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=3600"))
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(new byte[]{4, 5, 6}));
    }

    /**
     * GET /metadata/{id}/backdrop 元数据不存在：抛业务异常，包装为 R（body code=404）。
     */
    @Test
    void shouldRejectBackdropOfMissingMetadata() throws Exception {
        when(mediaMetadataMapper.selectById("meta-x")).thenReturn(null);

        mockMvc.perform(get("/jcloud/api/media/metadata/meta-x/backdrop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("背景图不存在"));
    }

    /**
     * GET /tmdb/search：mediaType/query/year 透传 TMDB 搜索服务，返回 R.ok 包装的搜索结果列表。
     */
    @Test
    void shouldSearchTmdb() throws Exception {
        TmdbSearchResultVo vo = new TmdbSearchResultVo();
        vo.setTmdbId(100L);
        vo.setMediaType("movie");
        vo.setTitle("星际穿越");
        when(tmdbService.search("movie", "星际", 2014)).thenReturn(List.of(vo));

        mockMvc.perform(get("/jcloud/api/media/tmdb/search")
                        .param("mediaType", "movie")
                        .param("query", "星际")
                        .param("year", "2014"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].tmdbId").value(100))
                .andExpect(jsonPath("$.data[0].title").value("星际穿越"));

        verify(tmdbService).search(eq("movie"), eq("星际"), eq(2014));
    }

    /**
     * GET /tmdb/search 缺 mediaType：缺少必填请求参数，包装为 R（body code=400），不触发 TMDB 服务。
     */
    @Test
    void shouldRejectTmdbSearchWithoutMediaType() throws Exception {
        mockMvc.perform(get("/jcloud/api/media/tmdb/search").param("query", "星际"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("缺少请求参数：mediaType")));

        verify(tmdbService, never()).search(any(), any(), any());
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
}
