package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.model.dto.UserSyncConfigUpdateDto;
import com.fleyx.jcloud.model.vo.UserSyncConfigVo;
import com.fleyx.jcloud.model.vo.UserSyncTaskVo;
import com.fleyx.jcloud.service.UserSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
 * 管理员用户存储空间同步控制器单元测试（路由、DTO 透传与 R 包装）。
 */
class AdminUserSyncControllerTest {

    private final UserSyncService userSyncService = mock(UserSyncService.class);

    private final AdminUserSyncController controller = new AdminUserSyncController(userSyncService);

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
     * POST /admin/users/{id}/sync/immediate：路径 id 透传，返回 R.ok 包装的同步任务视图。
     */
    @Test
    void shouldSubmitImmediateSync() throws Exception {
        UserSyncTaskVo vo = new UserSyncTaskVo();
        vo.setId("task-1");
        vo.setUserId("u1");
        vo.setType("manual");
        vo.setStatus("PENDING");
        when(userSyncService.submitImmediate("u1")).thenReturn(vo);

        mockMvc.perform(post("/jcloud/api/admin/users/u1/sync/immediate"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data.id").value("task-1"), jsonPath("$.data.userId").value("u1"),
                        jsonPath("$.data.type").value("manual"), jsonPath("$.data.status").value("PENDING"));

        verify(userSyncService).submitImmediate(eq("u1"));
    }

    /**
     * GET /admin/users/{id}/sync/task：路径 id 透传，返回 R.ok 包装的最新同步任务视图。
     */
    @Test
    void shouldReturnLatestSyncTask() throws Exception {
        UserSyncTaskVo vo = new UserSyncTaskVo();
        vo.setId("task-9");
        vo.setUserId("u1");
        vo.setStatus("RUNNING");
        vo.setTotalCount(100L);
        when(userSyncService.getLatestTask("u1")).thenReturn(vo);

        mockMvc.perform(get("/jcloud/api/admin/users/u1/sync/task"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data.id").value("task-9"), jsonPath("$.data.userId").value("u1"),
                        jsonPath("$.data.status").value("RUNNING"), jsonPath("$.data.totalCount").value(100));

        verify(userSyncService).getLatestTask(eq("u1"));
    }

    /**
     * GET /admin/users/{id}/sync/config：路径 id 透传，返回 R.ok 包装的同步配置视图。
     */
    @Test
    void shouldReturnSyncConfig() throws Exception {
        UserSyncConfigVo vo = new UserSyncConfigVo();
        vo.setUserId("u1");
        vo.setCronExpr("0 0 2 * *");
        vo.setEnabled(1);
        when(userSyncService.getConfig("u1")).thenReturn(vo);

        mockMvc.perform(get("/jcloud/api/admin/users/u1/sync/config"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data.userId").value("u1"), jsonPath("$.data.cronExpr").value("0 0 2 * *"),
                        jsonPath("$.data.enabled").value(1));

        verify(userSyncService).getConfig(eq("u1"));
    }

    /**
     * PUT /admin/users/{id}/sync/config：请求体占位 userId 被路径 id 覆盖，cronExpr/enabled 透传，返回 R.ok 包装的更新后配置。
     */
    @Test
    void shouldUpdateSyncConfigWithPathUserId() throws Exception {
        UserSyncConfigVo vo = new UserSyncConfigVo();
        vo.setUserId("u1");
        vo.setCronExpr("0 0 3 * *");
        vo.setEnabled(0);
        when(userSyncService.updateConfig(any(UserSyncConfigUpdateDto.class))).thenReturn(vo);

        mockMvc.perform(put("/jcloud/api/admin/users/u1/sync/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"ignored\",\"cronExpr\":\"0 0 3 * *\",\"enabled\":0}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data.userId").value("u1"), jsonPath("$.data.cronExpr").value("0 0 3 * *"),
                        jsonPath("$.data.enabled").value(0));

        ArgumentCaptor<UserSyncConfigUpdateDto> captor = ArgumentCaptor.forClass(UserSyncConfigUpdateDto.class);
        verify(userSyncService).updateConfig(captor.capture());
        assertEquals("u1", captor.getValue().getUserId());
        assertEquals("0 0 3 * *", captor.getValue().getCronExpr());
        assertEquals(0, captor.getValue().getEnabled());
    }

    /**
     * PUT /admin/users/{id}/sync/config 请求体缺必填字段：@Valid 校验失败经 GlobalExceptionHandler
     * 包装为 R（body code=400，msg 含校验消息），service 不被调用。
     */
    @Test
    void shouldRejectInvalidSyncConfigWithParamError() throws Exception {
        mockMvc.perform(put("/jcloud/api/admin/users/u1/sync/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()),
                        jsonPath("$.msg").value(containsString("cron 表达式不能为空")));

        verify(userSyncService, never()).updateConfig(any());
    }

    /**
     * POST /admin/users/{id}/sync/immediate service 抛 BusinessException：经 GlobalExceptionHandler
     * 包装为 R（body code=404，HTTP 状态仍 200）。
     */
    @Test
    void shouldWrapBusinessExceptionOnSubmitImmediate() throws Exception {
        when(userSyncService.submitImmediate("u404"))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "用户不存在"));

        mockMvc.perform(post("/jcloud/api/admin/users/u404/sync/immediate"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(404), jsonPath("$.msg").value("用户不存在"));

        verify(userSyncService).submitImmediate(eq("u404"));
    }
}
