package com.fleyx.jcloud.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.model.dto.RemoteMountPageQueryDto;
import com.fleyx.jcloud.model.dto.RemoteMountSaveDto;
import com.fleyx.jcloud.model.dto.RemoteMountSyncConfigUpdateDto;
import com.fleyx.jcloud.model.dto.RemoteMountUpdateDto;
import com.fleyx.jcloud.model.dto.RemoteSyncTaskPageQueryDto;
import com.fleyx.jcloud.model.vo.RemoteMountDetailVo;
import com.fleyx.jcloud.model.vo.RemoteMountHealthVo;
import com.fleyx.jcloud.model.vo.RemoteMountVo;
import com.fleyx.jcloud.model.vo.RemoteSyncTaskVo;
import com.fleyx.jcloud.service.RemoteMountService;
import com.fleyx.jcloud.service.RemoteMountSyncService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentCaptor.forClass;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 远程挂载控制器单元测试（路由、DTO 透传与 R 包装）。
 */
class RemoteMountControllerTest {

    private final RemoteMountService remoteMountService = mock(RemoteMountService.class);
    private final RemoteMountSyncService remoteMountSyncService = mock(RemoteMountSyncService.class);

    private final RemoteMountController controller =
            new RemoteMountController(remoteMountService, remoteMountSyncService);

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
     * GET /remote-mounts 透传分页查询条件与当前用户 ID，返回 R.ok 包装的分页数据。
     */
    @Test
    void shouldPageRemoteMountsWithQueryParams() throws Exception {
        RemoteMountVo vo = new RemoteMountVo();
        vo.setId("m-1");
        vo.setName("备份盘");
        vo.setType("webdav");
        vo.setEnabled(1);
        Page<RemoteMountVo> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(vo));
        when(remoteMountService.page(any(RemoteMountPageQueryDto.class), eq("user-1"))).thenReturn(page);

        mockMvc.perform(get("/jcloud/api/remote-mounts")
                        .param("name", "备份")
                        .param("pageNum", "2")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value("m-1"))
                .andExpect(jsonPath("$.data.records[0].name").value("备份盘"));

        var captor = forClass(RemoteMountPageQueryDto.class);
        verify(remoteMountService).page(captor.capture(), eq("user-1"));
        assertEquals("备份", captor.getValue().getName());
        assertEquals(2L, captor.getValue().getPageNum());
        assertEquals(20L, captor.getValue().getPageSize());
    }

    /**
     * POST /remote-mounts 创建：请求体 DTO（含用户名/密码）原样透传 service，返回 R.ok 包装的挂载视图。
     */
    @Test
    void shouldCreateRemoteMountWithDtoPassthrough() throws Exception {
        RemoteMountVo vo = new RemoteMountVo();
        vo.setId("m-2");
        vo.setName("备份盘");
        vo.setType("webdav");
        vo.setEnabled(1);
        when(remoteMountService.save(any(RemoteMountSaveDto.class), eq("user-1"))).thenReturn(vo);

        mockMvc.perform(post("/jcloud/api/remote-mounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"备份盘\",\"type\":\"webdav\",\"url\":\"https://dav.example.com\","
                                + "\"username\":\"u1\",\"password\":\"p1\",\"cronExpr\":\"0 0 * * *\",\"enabled\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("m-2"))
                .andExpect(jsonPath("$.data.name").value("备份盘"))
                .andExpect(jsonPath("$.data.type").value("webdav"))
                .andExpect(jsonPath("$.data.enabled").value(1));

        var captor = forClass(RemoteMountSaveDto.class);
        verify(remoteMountService).save(captor.capture(), eq("user-1"));
        assertEquals("备份盘", captor.getValue().getName());
        assertEquals("webdav", captor.getValue().getType());
        assertEquals("https://dav.example.com", captor.getValue().getUrl());
        assertEquals("u1", captor.getValue().getUsername());
        assertEquals("p1", captor.getValue().getPassword());
        assertEquals("0 0 * * *", captor.getValue().getCronExpr());
        assertEquals(1, captor.getValue().getEnabled());
    }

    /**
     * GET /remote-mounts/{id} 详情：透传 id 与用户 ID，凭据字段按 service 返回值原样透传（不在此层加密）。
     */
    @Test
    void shouldReturnMountDetailWithCredentialFields() throws Exception {
        RemoteMountDetailVo vo = new RemoteMountDetailVo();
        vo.setId("m-1");
        vo.setName("备份盘");
        vo.setUrl("https://dav.example.com");
        vo.setUsername("u1");
        vo.setPassword("p1");
        when(remoteMountService.detail("m-1", "user-1")).thenReturn(vo);

        mockMvc.perform(get("/jcloud/api/remote-mounts/m-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("m-1"))
                .andExpect(jsonPath("$.data.url").value("https://dav.example.com"))
                .andExpect(jsonPath("$.data.username").value("u1"))
                .andExpect(jsonPath("$.data.password").value("p1"));

        verify(remoteMountService).detail(eq("m-1"), eq("user-1"));
    }

    /**
     * PUT /remote-mounts/{id} 更新：路径 id 注入 DTO 后透传 service，返回 R.ok 包装的更新后视图。
     */
    @Test
    void shouldUpdateMountWithPathVariableId() throws Exception {
        RemoteMountVo vo = new RemoteMountVo();
        vo.setId("m-1");
        vo.setName("新名字");
        when(remoteMountService.update(any(RemoteMountUpdateDto.class), eq("user-1"))).thenReturn(vo);

        mockMvc.perform(put("/jcloud/api/remote-mounts/m-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"ignored\",\"name\":\"新名字\",\"type\":\"s3\",\"url\":\"https://s3.example.com\","
                                + "\"username\":\"u1\",\"password\":\"p2\",\"cronExpr\":\"0 0 * * *\",\"enabled\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("m-1"))
                .andExpect(jsonPath("$.data.name").value("新名字"));

        var captor = forClass(RemoteMountUpdateDto.class);
        verify(remoteMountService).update(captor.capture(), eq("user-1"));
        assertEquals("m-1", captor.getValue().getId());
        assertEquals("s3", captor.getValue().getType());
        assertEquals("https://s3.example.com", captor.getValue().getUrl());
        assertEquals(1, captor.getValue().getEnabled());
    }

    /**
     * DELETE /remote-mounts/{id} 删除：透传 id 与用户 ID，返回 R.ok（data 为 null）。
     */
    @Test
    void shouldDeleteMountAndReturnOk() throws Exception {
        mockMvc.perform(delete("/jcloud/api/remote-mounts/m-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(remoteMountService).delete(eq("m-1"), eq("user-1"));
    }

    /**
     * POST /remote-mounts/test-connection：表单 DTO（含用户名/密码）透传，返回 R.ok。
     */
    @Test
    void shouldTestConnectionWithFormDto() throws Exception {
        mockMvc.perform(post("/jcloud/api/remote-mounts/test-connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"备份盘\",\"type\":\"webdav\",\"url\":\"https://dav.example.com\","
                                + "\"username\":\"u1\",\"password\":\"p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        var captor = forClass(RemoteMountSaveDto.class);
        verify(remoteMountService).testConnection(captor.capture());
        assertEquals("备份盘", captor.getValue().getName());
        assertEquals("https://dav.example.com", captor.getValue().getUrl());
        assertEquals("u1", captor.getValue().getUsername());
        assertEquals("p1", captor.getValue().getPassword());
    }

    /**
     * POST /remote-mounts/{id}/sync 立即同步：透传 id 与用户 ID，返回 R.ok 包装的同步任务视图。
     */
    @Test
    void shouldSubmitImmediateSyncTask() throws Exception {
        RemoteSyncTaskVo task = new RemoteSyncTaskVo();
        task.setId("task-1");
        task.setRemoteMountId("m-1");
        task.setStatus("PENDING");
        when(remoteMountSyncService.submitImmediate("m-1", "user-1")).thenReturn(task);

        mockMvc.perform(post("/jcloud/api/remote-mounts/m-1/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("task-1"))
                .andExpect(jsonPath("$.data.remoteMountId").value("m-1"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        verify(remoteMountSyncService).submitImmediate(eq("m-1"), eq("user-1"));
    }

    /**
     * GET /remote-mounts/{id}/sync/task 最新任务：透传 id 与用户 ID，返回 R.ok 包装的任务视图。
     */
    @Test
    void shouldReturnLatestSyncTask() throws Exception {
        RemoteSyncTaskVo task = new RemoteSyncTaskVo();
        task.setId("task-9");
        task.setStatus("RUNNING");
        task.setTotalCount(100L);
        when(remoteMountSyncService.getLatestTask("m-1", "user-1")).thenReturn(task);

        mockMvc.perform(get("/jcloud/api/remote-mounts/m-1/sync/task"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("task-9"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.totalCount").value(100));

        verify(remoteMountSyncService).getLatestTask(eq("m-1"), eq("user-1"));
    }

    /**
     * PUT /remote-mounts/{id}/sync-config：路径 id 注入 DTO 后透传，返回 R.ok。
     */
    @Test
    void shouldUpdateSyncConfigWithMountIdInjected() throws Exception {
        mockMvc.perform(put("/jcloud/api/remote-mounts/m-1/sync-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remoteMountId\":\"ignored\",\"cronExpr\":\"0 0 2 * *\",\"enabled\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        var captor = forClass(RemoteMountSyncConfigUpdateDto.class);
        verify(remoteMountSyncService).updateConfig(eq("m-1"), captor.capture(), eq("user-1"));
        assertEquals("m-1", captor.getValue().getRemoteMountId());
        assertEquals("0 0 2 * *", captor.getValue().getCronExpr());
        assertEquals(1, captor.getValue().getEnabled());
    }

    /**
     * GET /remote-mounts/sync-tasks 任务历史：remoteMountId 传 null，分页条件透传，返回 R.ok 包装的分页数据。
     */
    @Test
    void shouldPageSyncTasksWithNullMountId() throws Exception {
        RemoteSyncTaskVo task = new RemoteSyncTaskVo();
        task.setId("task-1");
        task.setStatus("SUCCESS");
        Page<RemoteSyncTaskVo> page = new Page<>(3, 15);
        page.setTotal(1);
        page.setRecords(List.of(task));
        when(remoteMountSyncService.pageTasks(isNull(), eq("user-1"), any(RemoteSyncTaskPageQueryDto.class)))
                .thenReturn(page);

        mockMvc.perform(get("/jcloud/api/remote-mounts/sync-tasks")
                        .param("pageNum", "3")
                        .param("pageSize", "15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value("task-1"))
                .andExpect(jsonPath("$.data.records[0].status").value("SUCCESS"));

        var captor = forClass(RemoteSyncTaskPageQueryDto.class);
        verify(remoteMountSyncService).pageTasks(isNull(), eq("user-1"), captor.capture());
        assertEquals(3L, captor.getValue().getPageNum());
        assertEquals(15L, captor.getValue().getPageSize());
    }

    /**
     * POST /remote-mounts/health-check：透传当前用户 ID，返回 R.ok 包装的健康状态列表。
     */
    @Test
    void shouldCheckAllMountsHealth() throws Exception {
        RemoteMountHealthVo health = new RemoteMountHealthVo();
        health.setId("m-1");
        health.setName("备份盘");
        health.setStatus("OK");
        when(remoteMountService.healthCheck("user-1")).thenReturn(List.of(health));

        mockMvc.perform(post("/jcloud/api/remote-mounts/health-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value("m-1"))
                .andExpect(jsonPath("$.data[0].status").value("OK"));

        verify(remoteMountService).healthCheck(eq("user-1"));
    }

    /**
     * POST /remote-mounts 请求体缺少必填字段：@Valid 校验失败经 GlobalExceptionHandler
     * 包装为 R（body code=400，msg 含校验消息），service 不被调用。
     */
    @Test
    void shouldRejectInvalidCreateDtoWithParamError() throws Exception {
        mockMvc.perform(post("/jcloud/api/remote-mounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("挂载名称不能为空")))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(remoteMountService, never()).save(any(), any());
    }

    /**
     * service 抛 BusinessException：经 GlobalExceptionHandler 包装为 R（body code=404，HTTP 状态仍 200）。
     */
    @Test
    void shouldWrapBusinessExceptionOnDetail() throws Exception {
        when(remoteMountService.detail("m-9", "user-1"))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "挂载不存在"));

        mockMvc.perform(get("/jcloud/api/remote-mounts/m-9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("挂载不存在"));

        verify(remoteMountService).detail(eq("m-9"), eq("user-1"));
    }
}
