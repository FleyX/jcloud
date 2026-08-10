package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.model.dto.MediaDirectorySaveDto;
import com.fleyx.jcloud.model.dto.MediaDirectoryUpdateDto;
import com.fleyx.jcloud.model.vo.MediaDirectoryVo;
import com.fleyx.jcloud.service.MediaDirectoryService;
import com.fleyx.jcloud.service.MediaScanService;
import com.fleyx.jcloud.service.MediaScrapeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 媒体库目录控制器单元测试（路由、DTO 透传与 R 包装）。
 */
class MediaDirectoryControllerTest {

    private final MediaDirectoryService mediaDirectoryService = mock(MediaDirectoryService.class);
    private final MediaScanService mediaScanService = mock(MediaScanService.class);
    private final MediaScrapeService mediaScrapeService = mock(MediaScrapeService.class);

    private final MediaDirectoryController controller =
            new MediaDirectoryController(mediaDirectoryService, mediaScanService, mediaScrapeService);

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
     * GET /media/directories 透传当前用户 ID，返回 R.ok 包装的媒体库列表。
     */
    @Test
    void shouldListDirectoriesWithCurrentUserId() throws Exception {
        MediaDirectoryVo vo = new MediaDirectoryVo();
        vo.setId("d-1");
        vo.setName("电影库");
        vo.setMediaType("movie");
        vo.setItemCount(12L);
        vo.setCoverPosterUrl("https://img.example.com/cover.jpg");
        when(mediaDirectoryService.list("user-1")).thenReturn(List.of(vo));

        mockMvc.perform(get("/jcloud/api/media/directories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value("d-1"))
                .andExpect(jsonPath("$.data[0].name").value("电影库"))
                .andExpect(jsonPath("$.data[0].mediaType").value("movie"))
                .andExpect(jsonPath("$.data[0].itemCount").value(12))
                .andExpect(jsonPath("$.data[0].coverPosterUrl").value("https://img.example.com/cover.jpg"));

        verify(mediaDirectoryService).list(eq("user-1"));
    }

    /**
     * POST /media/directories 创建：请求体 DTO（来源目录/媒体类型/cron）原样透传 service，返回 R.ok 包装的媒体库视图。
     */
    @Test
    void shouldCreateDirectoryWithDtoPassthrough() throws Exception {
        MediaDirectoryVo vo = new MediaDirectoryVo();
        vo.setId("d-1");
        vo.setName("我的媒体库");
        vo.setMediaType("movie");
        when(mediaDirectoryService.save(any(MediaDirectorySaveDto.class), eq("user-1"))).thenReturn(vo);

        mockMvc.perform(post("/jcloud/api/media/directories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceFileNodeIds\":[\"n1\",\"n2\"],\"name\":\"我的媒体库\","
                                + "\"mediaType\":\"movie\",\"scanCron\":\"0 0 2 * *\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("d-1"))
                .andExpect(jsonPath("$.data.name").value("我的媒体库"));

        var captor = forClass(MediaDirectorySaveDto.class);
        verify(mediaDirectoryService).save(captor.capture(), eq("user-1"));
        assertEquals(2, captor.getValue().getSourceFileNodeIds().size());
        assertEquals("n1", captor.getValue().getSourceFileNodeIds().get(0));
        assertEquals("我的媒体库", captor.getValue().getName());
        assertEquals("movie", captor.getValue().getMediaType());
        assertEquals("0 0 2 * *", captor.getValue().getScanCron());
    }

    /**
     * PUT /media/directories/{id} 更新：路径 id 注入 DTO 后透传 service，返回 R.ok 包装的更新后视图。
     */
    @Test
    void shouldUpdateDirectoryWithPathVariableId() throws Exception {
        MediaDirectoryVo vo = new MediaDirectoryVo();
        vo.setId("d-1");
        vo.setName("新名字");
        when(mediaDirectoryService.update(any(MediaDirectoryUpdateDto.class), eq("user-1"))).thenReturn(vo);

        mockMvc.perform(put("/jcloud/api/media/directories/d-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"ignored\",\"sourceFileNodeIds\":[\"n3\"],\"name\":\"新名字\","
                                + "\"mediaType\":\"tv\",\"scanCron\":\"0 0 3 * *\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("d-1"))
                .andExpect(jsonPath("$.data.name").value("新名字"));

        var captor = forClass(MediaDirectoryUpdateDto.class);
        verify(mediaDirectoryService).update(captor.capture(), eq("user-1"));
        assertEquals("d-1", captor.getValue().getId());
        assertEquals(1, captor.getValue().getSourceFileNodeIds().size());
        assertEquals("新名字", captor.getValue().getName());
        assertEquals("tv", captor.getValue().getMediaType());
        assertEquals("0 0 3 * *", captor.getValue().getScanCron());
    }

    /**
     * DELETE /media/directories/{id} 删除：透传 id 与用户 ID，返回 R.ok（data 为 null）。
     */
    @Test
    void shouldDeleteDirectoryAndReturnOk() throws Exception {
        mockMvc.perform(delete("/jcloud/api/media/directories/d-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(mediaDirectoryService).delete(eq("d-1"), eq("user-1"));
    }

    /**
     * POST /media/directories/{id}/scan：透传 id 与用户 ID，返回 R.ok（data 为 null）。
     */
    @Test
    void shouldSubmitScanAndReturnOk() throws Exception {
        mockMvc.perform(post("/jcloud/api/media/directories/d-1/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(mediaScanService).submitScan(eq("d-1"), eq("user-1"));
    }

    /**
     * POST /media/directories/{id}/scrape 缺省 force=false：force 默认值透传，返回 R.ok（data 为 null）。
     */
    @Test
    void shouldSubmitScrapeWithDefaultForceFalse() throws Exception {
        mockMvc.perform(post("/jcloud/api/media/directories/d-1/scrape"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(mediaScrapeService).submitScrape(eq("d-1"), eq("user-1"), eq(false));
    }

    /**
     * POST /media/directories/{id}/scrape?force=true：force 参数透传，返回 R.ok（data 为 null）。
     */
    @Test
    void shouldSubmitScrapeWithForceParam() throws Exception {
        mockMvc.perform(post("/jcloud/api/media/directories/d-1/scrape").param("force", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(mediaScrapeService).submitScrape(eq("d-1"), eq("user-1"), eq(true));
    }

    /**
     * POST /media/directories 请求体缺少必填字段：@Valid 校验失败经 GlobalExceptionHandler
     * 包装为 R（body code=400，msg 含校验消息），service 不被调用。
     */
    @Test
    void shouldRejectInvalidCreateDtoWithParamError() throws Exception {
        mockMvc.perform(post("/jcloud/api/media/directories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("来源目录不能为空")))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(mediaDirectoryService, never()).save(any(), any());
    }

    /**
     * POST /media/directories 媒体类型不合法：@Pattern 校验失败，返回 code=400。
     */
    @Test
    void shouldRejectInvalidMediaTypeWithParamError() throws Exception {
        mockMvc.perform(post("/jcloud/api/media/directories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceFileNodeIds\":[\"n1\"],\"mediaType\":\"book\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("媒体类型仅支持 movie/tv/other")))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(mediaDirectoryService, never()).save(any(), any());
    }

    /**
     * PUT /media/directories/{id} 请求体缺少必填字段：@Valid 校验失败（在路径 id 注入 DTO 之前），
     * 返回 code=400，service 不被调用。
     */
    @Test
    void shouldRejectInvalidUpdateDtoWithParamError() throws Exception {
        mockMvc.perform(put("/jcloud/api/media/directories/d-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("来源目录不能为空")))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(mediaDirectoryService, never()).update(any(), any());
    }

    /**
     * POST /media/directories/{id}/scrape force 非布尔值：类型不匹配经 GlobalExceptionHandler
     * 包装为 code=400，service 不被调用。
     */
    @Test
    void shouldReturnParamErrorWhenForceNotBoolean() throws Exception {
        mockMvc.perform(post("/jcloud/api/media/directories/d-1/scrape").param("force", "abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("参数类型不匹配：force")))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(mediaScrapeService, never()).submitScrape(any(), any(), anyBoolean());
    }

    /**
     * service 抛 BusinessException：经 GlobalExceptionHandler 包装为 R（body code=404，HTTP 状态仍 200）。
     */
    @Test
    void shouldWrapBusinessExceptionOnDelete() throws Exception {
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "媒体库不存在"))
                .when(mediaDirectoryService).delete("d-9", "user-1");

        mockMvc.perform(delete("/jcloud/api/media/directories/d-9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("媒体库不存在"))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(mediaDirectoryService).delete(eq("d-9"), eq("user-1"));
    }
}
