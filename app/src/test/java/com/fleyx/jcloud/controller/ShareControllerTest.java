package com.fleyx.jcloud.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.model.dto.ShareCreateDto;
import com.fleyx.jcloud.model.dto.SharePageQueryDto;
import com.fleyx.jcloud.model.dto.ShareUpdateDto;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.ShareDetailVo;
import com.fleyx.jcloud.model.vo.ShareVo;
import com.fleyx.jcloud.service.ShareService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分享管理控制器测试（登录态创建/分页/详情/更新/删除与校验包装）。
 */
class ShareControllerTest {

    private final ShareService shareService = mock(ShareService.class);

    private final ShareController controller = new ShareController(shareService);

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
     * GET /jcloud/api/shares 分页查询当前用户分享：name/status 绑定到 DTO，userId 取自 UserContext。
     */
    @Test
    void shouldPageSharesOfCurrentUser() throws Exception {
        ShareVo vo = new ShareVo();
        vo.setId("s1");
        vo.setName("测试分享");
        Page<ShareVo> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(vo));
        when(shareService.page(any(SharePageQueryDto.class), eq("user-1"))).thenReturn(page);

        mockMvc.perform(get("/jcloud/api/shares")
                        .param("name", "测试")
                        .param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].id").value("s1"))
                .andExpect(jsonPath("$.data.records[0].name").value("测试分享"));

        ArgumentCaptor<SharePageQueryDto> captor = ArgumentCaptor.forClass(SharePageQueryDto.class);
        verify(shareService).page(captor.capture(), eq("user-1"));
        SharePageQueryDto dto = captor.getValue();
        assertEquals("测试", dto.getName());
        assertEquals(1, dto.getStatus());
    }

    /**
     * POST /jcloud/api/shares 创建分享：fileNodeIds 列表正确反序列化，userId 取自 UserContext 并透传。
     */
    @Test
    void shouldCreateShareWithFileNodeIds() throws Exception {
        ShareVo vo = new ShareVo();
        vo.setId("s1");
        vo.setName("测试分享");
        when(shareService.create(any(ShareCreateDto.class), eq("user-1"))).thenReturn(vo);

        mockMvc.perform(post("/jcloud/api/shares")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"测试分享\",\"fileNodeIds\":[\"fn-1\",\"fn-2\"],"
                                + "\"password\":\"123456\",\"maxViews\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("s1"))
                .andExpect(jsonPath("$.data.name").value("测试分享"));

        ArgumentCaptor<ShareCreateDto> captor = ArgumentCaptor.forClass(ShareCreateDto.class);
        verify(shareService).create(captor.capture(), eq("user-1"));
        ShareCreateDto dto = captor.getValue();
        assertEquals("测试分享", dto.getName());
        assertEquals(List.of("fn-1", "fn-2"), dto.getFileNodeIds());
        assertEquals("123456", dto.getPassword());
        assertEquals(10L, dto.getMaxViews());
    }

    /**
     * GET /jcloud/api/shares/{id} 查看分享详情：路径 ID 与 UserContext 用户透传，返回详情视图。
     */
    @Test
    void shouldGetShareDetail() throws Exception {
        ShareDetailVo vo = new ShareDetailVo();
        vo.setId("s1");
        vo.setName("测试分享");
        FileNodeVo node = new FileNodeVo();
        node.setId("fn-1");
        node.setName("报告.docx");
        vo.setItems(List.of(node));
        when(shareService.detail("s1", "user-1")).thenReturn(vo);

        mockMvc.perform(get("/jcloud/api/shares/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("s1"))
                .andExpect(jsonPath("$.data.items[0].id").value("fn-1"))
                .andExpect(jsonPath("$.data.items[0].name").value("报告.docx"));

        verify(shareService).detail(eq("s1"), eq("user-1"));
    }

    /**
     * PUT /jcloud/api/shares/{id} 更新分享：路径 ID、更新 DTO 与 UserContext 用户透传。
     */
    @Test
    void shouldUpdateShare() throws Exception {
        ShareVo vo = new ShareVo();
        vo.setId("s1");
        when(shareService.update(eq("s1"), any(ShareUpdateDto.class), eq("user-1"))).thenReturn(vo);

        mockMvc.perform(put("/jcloud/api/shares/s1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"更新分享\",\"fileNodeIds\":[\"fn-3\"],\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("s1"));

        ArgumentCaptor<ShareUpdateDto> captor = ArgumentCaptor.forClass(ShareUpdateDto.class);
        verify(shareService).update(eq("s1"), captor.capture(), eq("user-1"));
        ShareUpdateDto dto = captor.getValue();
        assertEquals("更新分享", dto.getName());
        assertEquals(List.of("fn-3"), dto.getFileNodeIds());
        assertEquals(0, dto.getStatus());
    }

    /**
     * DELETE /jcloud/api/shares/{id} 删除分享：路径 ID 与 UserContext 用户透传，返回 R.ok 空数据。
     */
    @Test
    void shouldDeleteShare() throws Exception {
        mockMvc.perform(delete("/jcloud/api/shares/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(shareService).delete(eq("s1"), eq("user-1"));
    }

    /**
     * POST /jcloud/api/shares 缺 fileNodeIds：@Valid 校验失败，GlobalExceptionHandler 包装为 code=400。
     */
    @Test
    void shouldRejectCreateWithoutFileNodeIds() throws Exception {
        mockMvc.perform(post("/jcloud/api/shares")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"测试分享\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("至少选择一个文件或文件夹")));
    }

    /**
     * GET /jcloud/api/shares/{id} 分享不存在：service 抛业务异常，包装为 R（body code=404）。
     */
    @Test
    void shouldWrapNotFoundWhenShareMissing() throws Exception {
        when(shareService.detail("s404", "user-1"))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "分享不存在"));

        mockMvc.perform(get("/jcloud/api/shares/s404"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("分享不存在"));
    }
}
