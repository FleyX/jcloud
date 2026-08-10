package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.model.dto.SystemInitDto;
import com.fleyx.jcloud.model.vo.SystemInitStatusVo;
import com.fleyx.jcloud.service.SystemInitService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 系统初始化控制器单元测试（路由、DTO 透传与 R 包装）。
 */
class SystemInitControllerTest {

    private final SystemInitService systemInitService = mock(SystemInitService.class);

    private final SystemInitController controller = new SystemInitController(systemInitService);

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    /**
     * GET /admin/system/init-status：返回 R.ok 包装的初始化状态。
     */
    @Test
    void shouldReturnInitStatus() throws Exception {
        SystemInitStatusVo vo = new SystemInitStatusVo();
        vo.setInitialized(true);
        vo.setAdmin(true);
        when(systemInitService.getInitStatus()).thenReturn(vo);

        mockMvc.perform(get("/jcloud/api/admin/system/init-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.initialized").value(true))
                .andExpect(jsonPath("$.data.admin").value(true));

        verify(systemInitService).getInitStatus();
    }

    /**
     * POST /admin/system/initialize：请求体 DTO（含嵌套空间列表）原样透传 service，返回 R.ok（data 为 null）。
     */
    @Test
    void shouldInitializePassingDto() throws Exception {
        mockMvc.perform(post("/jcloud/api/admin/system/initialize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaces\":[{\"name\":\"数据盘\",\"path\":\"/data/jcloud\",\"remark\":\"主存储\"},"
                                + "{\"name\":\"系统盘\",\"path\":\"/data/system\"}],"
                                + "\"primaryIndex\":0,\"systemDataIndex\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        var captor = forClass(SystemInitDto.class);
        verify(systemInitService).initialize(captor.capture());
        assertEquals(2, captor.getValue().getSpaces().size());
        assertEquals("数据盘", captor.getValue().getSpaces().get(0).getName());
        assertEquals("/data/jcloud", captor.getValue().getSpaces().get(0).getPath());
        assertEquals("主存储", captor.getValue().getSpaces().get(0).getRemark());
        assertEquals("系统盘", captor.getValue().getSpaces().get(1).getName());
        assertEquals("/data/system", captor.getValue().getSpaces().get(1).getPath());
        assertEquals(0, captor.getValue().getPrimaryIndex());
        assertEquals(1, captor.getValue().getSystemDataIndex());
    }

    /**
     * POST /admin/system/initialize 存储空间为空：@Valid 校验失败经 GlobalExceptionHandler
     * 包装为 R（body code=400，msg 含校验消息），service 不被调用。
     */
    @Test
    void shouldRejectInitializeWithEmptySpaces() throws Exception {
        mockMvc.perform(post("/jcloud/api/admin/system/initialize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaces\":[],\"primaryIndex\":0,\"systemDataIndex\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("至少配置一个存储空间")))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(systemInitService, never()).initialize(any());
    }

    /**
     * POST /admin/system/initialize 缺少主存储空间索引：@Valid 校验失败包装为 R（body code=400）。
     */
    @Test
    void shouldRejectInitializeWithMissingPrimaryIndex() throws Exception {
        mockMvc.perform(post("/jcloud/api/admin/system/initialize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaces\":[{\"name\":\"数据盘\",\"path\":\"/data/jcloud\"}],\"systemDataIndex\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("必须指定主存储空间")))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(systemInitService, never()).initialize(any());
    }

    /**
     * POST /admin/system/initialize 存储空间名称缺失：嵌套 @Valid 校验失败包装为 R（body code=400）。
     */
    @Test
    void shouldRejectInitializeWithBlankSpaceName() throws Exception {
        mockMvc.perform(post("/jcloud/api/admin/system/initialize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaces\":[{\"name\":\"\",\"path\":\"/data/jcloud\"}],"
                                + "\"primaryIndex\":0,\"systemDataIndex\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("存储空间名称不能为空")))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(systemInitService, never()).initialize(any());
    }

    /**
     * GET /admin/system/init-status 查询时 service 抛 BusinessException：
     * 经 GlobalExceptionHandler 包装为 R（body code=500，HTTP 状态仍 200）。
     */
    @Test
    void shouldWrapBusinessExceptionOnGetInitStatus() throws Exception {
        when(systemInitService.getInitStatus())
                .thenThrow(new BusinessException(ResultCode.BUSINESS_ERROR, "系统尚未初始化"));

        mockMvc.perform(get("/jcloud/api/admin/system/init-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("系统尚未初始化"));

        verify(systemInitService).getInitStatus();
    }

    /**
     * POST /admin/system/initialize 执行时 service 抛 BusinessException：
     * 经 GlobalExceptionHandler 包装为 R（body code=500，HTTP 状态仍 200）。
     */
    @Test
    void shouldWrapBusinessExceptionOnInitialize() throws Exception {
        doThrow(new BusinessException(ResultCode.BUSINESS_ERROR, "系统已完成初始化，禁止重复初始化"))
                .when(systemInitService).initialize(any());

        mockMvc.perform(post("/jcloud/api/admin/system/initialize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaces\":[{\"name\":\"数据盘\",\"path\":\"/data/jcloud\"}],"
                                + "\"primaryIndex\":0,\"systemDataIndex\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("系统已完成初始化，禁止重复初始化"));

        verify(systemInitService).initialize(any());
    }
}
