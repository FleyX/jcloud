package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.model.dto.UserMigrationSubmitDto;
import com.fleyx.jcloud.model.vo.UserMigrationTaskVo;
import com.fleyx.jcloud.service.UserMigrationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
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
 * 用户存储空间迁移管理控制器单元测试（路由、DTO 透传与 R 包装）。
 */
class AdminUserMigrationControllerTest {

    private final UserMigrationService userMigrationService = mock(UserMigrationService.class);

    private final AdminUserMigrationController controller = new AdminUserMigrationController(userMigrationService);

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
     * POST /admin/users/{id}/migrate：请求体占位 userId 被路径 ID 覆盖，targetSpaceId/newQuota 透传，
     * 返回 R.ok 包装的迁移任务视图。
     */
    @Test
    void shouldSubmitMigrationOverridingPathUserId() throws Exception {
        UserMigrationTaskVo vo = new UserMigrationTaskVo();
        vo.setId("task-1");
        vo.setUserId("u-1");
        vo.setTargetSpaceId("s2");
        vo.setStatus("PENDING");
        when(userMigrationService.submitMigration(any(UserMigrationSubmitDto.class))).thenReturn(vo);

        mockMvc.perform(post("/jcloud/api/admin/users/u-1/migrate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"ignored\",\"targetSpaceId\":\"s2\",\"newQuota\":1024}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("task-1"))
                .andExpect(jsonPath("$.data.userId").value("u-1"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        var captor = forClass(UserMigrationSubmitDto.class);
        verify(userMigrationService).submitMigration(captor.capture());
        assertEquals("u-1", captor.getValue().getUserId());
        assertEquals("s2", captor.getValue().getTargetSpaceId());
        assertEquals(1024L, captor.getValue().getNewQuota());
    }

    /**
     * POST /admin/users/{id}/migrate 缺少目标存储空间：@Valid 校验失败，GlobalExceptionHandler 包装为 code=400。
     */
    @Test
    void shouldRejectMigrationWithoutTargetSpaceId() throws Exception {
        mockMvc.perform(post("/jcloud/api/admin/users/u-1/migrate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("目标存储空间 ID 不能为空")));

        verify(userMigrationService, never()).submitMigration(any());
    }

    /**
     * POST /admin/users/{id}/migrate 缺少占位 userId：@Valid 在路径 ID 注入之前执行，userId 校验仍生效，
     * 缺少时同样被拦截为 code=400（service 不被调用）。
     */
    @Test
    void shouldRejectMigrationWithoutUserIdPlaceholder() throws Exception {
        mockMvc.perform(post("/jcloud/api/admin/users/u-1/migrate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetSpaceId\":\"s2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("用户 ID 不能为空")));

        verify(userMigrationService, never()).submitMigration(any());
    }

    /**
     * POST /admin/users/{id}/migrate newQuota 非法（0）：@Min 校验失败，包装为 code=400。
     */
    @Test
    void shouldRejectMigrationWithInvalidNewQuota() throws Exception {
        mockMvc.perform(post("/jcloud/api/admin/users/u-1/migrate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u-1\",\"targetSpaceId\":\"s2\",\"newQuota\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("新配额必须大于 0")));

        verify(userMigrationService, never()).submitMigration(any());
    }

    /**
     * POST /admin/users/{id}/migrate service 抛 BusinessException：经 GlobalExceptionHandler 包装为 R（body code=404）。
     */
    @Test
    void shouldWrapBusinessExceptionOnMigrationSubmit() throws Exception {
        when(userMigrationService.submitMigration(any(UserMigrationSubmitDto.class)))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "用户不存在"));

        mockMvc.perform(post("/jcloud/api/admin/users/u-404/migrate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u-404\",\"targetSpaceId\":\"s2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("用户不存在"));

        verify(userMigrationService).submitMigration(any(UserMigrationSubmitDto.class));
    }

    /**
     * GET /admin/users/{id}/migration-task：透传路径 ID，返回 R.ok 包装的最新迁移任务视图。
     */
    @Test
    void shouldReturnLatestMigrationTaskByUserId() throws Exception {
        UserMigrationTaskVo vo = new UserMigrationTaskVo();
        vo.setId("task-9");
        vo.setUserId("u-1");
        vo.setStatus("RUNNING");
        vo.setTotalBytes(2048L);
        vo.setMigratedBytes(1024L);
        when(userMigrationService.getLatestTaskByUserId("u-1")).thenReturn(vo);

        mockMvc.perform(get("/jcloud/api/admin/users/u-1/migration-task"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("task-9"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.totalBytes").value(2048))
                .andExpect(jsonPath("$.data.migratedBytes").value(1024));

        verify(userMigrationService).getLatestTaskByUserId(eq("u-1"));
    }

    /**
     * GET /admin/users/{id}/migration-task 无历史任务：service 返回 null，R.ok 包装 null 数据。
     */
    @Test
    void shouldReturnNullDataWhenNoMigrationTask() throws Exception {
        when(userMigrationService.getLatestTaskByUserId("u-2")).thenReturn(null);

        mockMvc.perform(get("/jcloud/api/admin/users/u-2/migration-task"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(userMigrationService).getLatestTaskByUserId(eq("u-2"));
    }

    /**
     * GET /admin/users/{id}/migration-task service 抛 BusinessException：经 GlobalExceptionHandler 包装为 R（body code=404）。
     */
    @Test
    void shouldWrapBusinessExceptionOnGetLatestTask() throws Exception {
        when(userMigrationService.getLatestTaskByUserId("u-404"))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "用户不存在"));

        mockMvc.perform(get("/jcloud/api/admin/users/u-404/migration-task"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("用户不存在"));

        verify(userMigrationService).getLatestTaskByUserId(eq("u-404"));
    }
}
