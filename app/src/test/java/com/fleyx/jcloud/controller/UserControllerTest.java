package com.fleyx.jcloud.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.model.dto.BatchUserStatusDto;
import com.fleyx.jcloud.model.dto.ChangePasswordDto;
import com.fleyx.jcloud.model.dto.UserPageQueryDto;
import com.fleyx.jcloud.model.dto.UserProfileUpdateDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.dto.UserStatusDto;
import com.fleyx.jcloud.model.dto.UserStorageDto;
import com.fleyx.jcloud.model.dto.UserUpdateDto;
import com.fleyx.jcloud.model.dto.UserUpdateRolesDto;
import com.fleyx.jcloud.model.dto.UserWebDavToggleDto;
import com.fleyx.jcloud.model.vo.UserProfileVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.UserService;
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
 * 用户管理控制器单元测试（无 Spring 上下文，参数透传与响应包装）。
 */
class UserControllerTest {

    private final UserService userService = mock(UserService.class);

    private final UserController controller = new UserController(userService);

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
     * POST /users：新增用户 DTO 逐字段透传，返回 R.ok 包装的用户视图。
     */
    @Test
    void shouldSaveUser() throws Exception {
        UserSaveDto expected = new UserSaveDto();
        expected.setUsername("zhangsan");
        expected.setPassword("123456");
        expected.setEmail("zhang@example.com");
        expected.setNickname("张三");
        expected.setStorageSpaceId("s1");
        expected.setQuota(1024L);
        expected.setQuotaUnit("GB");

        UserVo vo = new UserVo();
        vo.setId("u1");
        vo.setUsername("zhangsan");
        when(userService.saveUser(eq(expected))).thenReturn(vo);

        mockMvc.perform(post("/jcloud/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"zhangsan\",\"password\":\"123456\",\"email\":\"zhang@example.com\","
                                + "\"nickname\":\"张三\",\"storageSpaceId\":\"s1\",\"quota\":1024,\"quotaUnit\":\"GB\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("u1"))
                .andExpect(jsonPath("$.data.username").value("zhangsan"));

        verify(userService).saveUser(eq(expected));
    }

    /**
     * GET /users/{id}：路径 ID 透传，返回 R.ok 包装的用户视图。
     */
    @Test
    void shouldGetUserById() throws Exception {
        UserVo vo = new UserVo();
        vo.setId("u1");
        vo.setUsername("zhangsan");
        when(userService.getById("u1")).thenReturn(vo);

        mockMvc.perform(get("/jcloud/api/users/u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("u1"))
                .andExpect(jsonPath("$.data.username").value("zhangsan"));

        verify(userService).getById(eq("u1"));
    }

    /**
     * GET /users/search：username 关键字透传，返回 R.ok 包装的用户列表。
     */
    @Test
    void shouldSearchUsersByUsername() throws Exception {
        UserVo vo = new UserVo();
        vo.setId("u2");
        vo.setUsername("admin2");
        when(userService.listByUsername("admin")).thenReturn(List.of(vo));

        mockMvc.perform(get("/jcloud/api/users/search").param("username", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value("u2"))
                .andExpect(jsonPath("$.data[0].username").value("admin2"));

        verify(userService).listByUsername(eq("admin"));
    }

    /**
     * GET /users：分页条件绑定到 UserPageQueryDto 并透传，返回 R.ok 包装的分页数据。
     */
    @Test
    void shouldPageUsersWithQueryParams() throws Exception {
        UserPageQueryDto expected = new UserPageQueryDto();
        expected.setUsername("zhang");
        expected.setStatus(1);
        expected.setPageNum(2L);
        expected.setPageSize(50L);

        UserVo vo = new UserVo();
        vo.setId("u1");
        Page<UserVo> page = new Page<>(2, 50);
        page.setRecords(List.of(vo));
        when(userService.pageUsers(eq(expected))).thenReturn(page);

        mockMvc.perform(get("/jcloud/api/users")
                        .param("username", "zhang")
                        .param("status", "1")
                        .param("pageNum", "2")
                        .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].id").value("u1"));

        verify(userService).pageUsers(eq(expected));
    }

    /**
     * GET /users 不带分页参数：pageNum/pageSize 使用 DTO 默认值（1/20）透传。
     */
    @Test
    void shouldPageUsersWithDefaultPaging() throws Exception {
        UserPageQueryDto expected = new UserPageQueryDto();
        Page<UserVo> page = new Page<>(1, 20);
        page.setRecords(List.of());
        when(userService.pageUsers(eq(expected))).thenReturn(page);

        mockMvc.perform(get("/jcloud/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records.length()").value(0));

        ArgumentCaptor<UserPageQueryDto> captor = ArgumentCaptor.forClass(UserPageQueryDto.class);
        verify(userService).pageUsers(captor.capture());
        assertEquals(1L, captor.getValue().getPageNum());
        assertEquals(20L, captor.getValue().getPageSize());
    }

    /**
     * PUT /users/{id}/roles：路径 ID 回填到 DTO.userId，roleIds 透传，返回 R.ok 空数据。
     */
    @Test
    void shouldUpdateRolesWithPathId() throws Exception {
        mockMvc.perform(put("/jcloud/api/users/u1/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[\"r1\",\"r2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        ArgumentCaptor<UserUpdateRolesDto> captor = ArgumentCaptor.forClass(UserUpdateRolesDto.class);
        verify(userService).updateRoles(captor.capture());
        assertEquals("u1", captor.getValue().getUserId());
        assertEquals(List.of("r1", "r2"), captor.getValue().getRoleIds());
    }

    /**
     * PUT /users/{id}：路径 ID 回填到 DTO.id，昵称/邮箱/状态透传，返回更新后的用户视图。
     */
    @Test
    void shouldUpdateUserWithPathId() throws Exception {
        UserUpdateDto expected = new UserUpdateDto();
        expected.setId("u1");
        expected.setNickname("新昵称");
        expected.setEmail("new@example.com");
        expected.setStatus(1);

        UserVo vo = new UserVo();
        vo.setId("u1");
        vo.setNickname("新昵称");
        when(userService.updateUser(eq(expected))).thenReturn(vo);

        mockMvc.perform(put("/jcloud/api/users/u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"新昵称\",\"email\":\"new@example.com\",\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("u1"));

        ArgumentCaptor<UserUpdateDto> captor = ArgumentCaptor.forClass(UserUpdateDto.class);
        verify(userService).updateUser(captor.capture());
        assertEquals("u1", captor.getValue().getId());
        assertEquals("新昵称", captor.getValue().getNickname());
    }

    /**
     * PUT /users/{id}/status：路径 ID 回填到 DTO.userId，status 透传，返回 R.ok 空数据。
     */
    @Test
    void shouldUpdateStatusWithPathId() throws Exception {
        mockMvc.perform(put("/jcloud/api/users/u1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        ArgumentCaptor<UserStatusDto> captor = ArgumentCaptor.forClass(UserStatusDto.class);
        verify(userService).updateStatus(captor.capture());
        assertEquals("u1", captor.getValue().getUserId());
        assertEquals(0, captor.getValue().getStatus());
    }

    /**
     * DELETE /users/{id}：路径 ID 透传，返回 R.ok 包装的删除结果。
     */
    @Test
    void shouldRemoveUserById() throws Exception {
        when(userService.removeById("u1")).thenReturn(true);

        mockMvc.perform(delete("/jcloud/api/users/u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        verify(userService).removeById(eq("u1"));
    }

    /**
     * DELETE /users/batch：请求体用户 ID 列表透传，返回 R.ok 包装的实际删除列表。
     */
    @Test
    void shouldBatchRemoveUsers() throws Exception {
        when(userService.batchDelete(List.of("u1", "u2"))).thenReturn(List.of("u1", "u2"));

        mockMvc.perform(delete("/jcloud/api/users/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"u1\",\"u2\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0]").value("u1"))
                .andExpect(jsonPath("$.data[1]").value("u2"));

        verify(userService).batchDelete(eq(List.of("u1", "u2")));
    }

    /**
     * PUT /users/batch/status：批量状态 DTO 透传，返回 R.ok 包装的实际更新列表。
     */
    @Test
    void shouldBatchUpdateStatus() throws Exception {
        BatchUserStatusDto expected = new BatchUserStatusDto();
        expected.setUserIds(List.of("u1", "u2"));
        expected.setStatus(1);
        when(userService.batchUpdateStatus(eq(expected))).thenReturn(List.of("u1"));

        mockMvc.perform(put("/jcloud/api/users/batch/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[\"u1\",\"u2\"],\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0]").value("u1"));

        verify(userService).batchUpdateStatus(eq(expected));
    }

    /**
     * GET /users/me：透传 UserContext 中的当前用户 ID，返回 R.ok 包装的个人信息。
     */
    @Test
    void shouldGetCurrentProfile() throws Exception {
        UserProfileVo vo = new UserProfileVo();
        vo.setId("user-1");
        vo.setUsername("admin");
        when(userService.getUserProfile("user-1")).thenReturn(vo);

        mockMvc.perform(get("/jcloud/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("user-1"))
                .andExpect(jsonPath("$.data.username").value("admin"));

        verify(userService).getUserProfile(eq("user-1"));
    }

    /**
     * PUT /users/me：当前用户 ID 与个人信息 DTO 透传，返回更新后的个人信息视图。
     */
    @Test
    void shouldUpdateCurrentProfile() throws Exception {
        UserProfileUpdateDto expected = new UserProfileUpdateDto();
        expected.setEmail("x@example.com");
        expected.setNickname("新昵称");

        UserProfileVo vo = new UserProfileVo();
        vo.setId("user-1");
        vo.setNickname("新昵称");
        when(userService.updateUserProfile(eq("user-1"), eq(expected))).thenReturn(vo);

        mockMvc.perform(put("/jcloud/api/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@example.com\",\"nickname\":\"新昵称\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("user-1"));

        verify(userService).updateUserProfile(eq("user-1"), eq(expected));
    }

    /**
     * PUT /users/me/password：当前用户 ID 与改密 DTO 透传，返回 R.ok 空数据。
     */
    @Test
    void shouldChangeCurrentPassword() throws Exception {
        mockMvc.perform(put("/jcloud/api/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old-pass\",\"newPassword\":\"new-pass\","
                                + "\"confirmPassword\":\"new-pass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        ArgumentCaptor<ChangePasswordDto> captor = ArgumentCaptor.forClass(ChangePasswordDto.class);
        verify(userService).changePassword(eq("user-1"), captor.capture());
        assertEquals("old-pass", captor.getValue().getCurrentPassword());
        assertEquals("new-pass", captor.getValue().getNewPassword());
        assertEquals("new-pass", captor.getValue().getConfirmPassword());
    }

    /**
     * PUT /users/me/webdav：当前用户 ID 与开关状态透传，返回更新后的个人信息视图。
     */
    @Test
    void shouldToggleWebDav() throws Exception {
        UserWebDavToggleDto expected = new UserWebDavToggleDto();
        expected.setEnabled(true);

        UserProfileVo vo = new UserProfileVo();
        vo.setId("user-1");
        vo.setWebdavEnabled(true);
        when(userService.toggleWebDav(eq("user-1"), eq(true))).thenReturn(vo);

        mockMvc.perform(put("/jcloud/api/users/me/webdav")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("user-1"))
                .andExpect(jsonPath("$.data.webdavEnabled").value(true));

        verify(userService).toggleWebDav(eq("user-1"), eq(true));
    }

    /**
     * PUT /users/{id}/storage：请求体 userId 被路径 ID 覆盖，storageSpaceId/quota 透传，返回 R.ok 空数据。
     */
    @Test
    void shouldBindStorageSpaceOverridingPathUserId() throws Exception {
        mockMvc.perform(put("/jcloud/api/users/u1/storage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"other\",\"storageSpaceId\":\"s1\",\"quota\":1024}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        ArgumentCaptor<UserStorageDto> captor = ArgumentCaptor.forClass(UserStorageDto.class);
        verify(userService).bindStorageSpace(captor.capture());
        assertEquals("u1", captor.getValue().getUserId());
        assertEquals("s1", captor.getValue().getStorageSpaceId());
        assertEquals(1024L, captor.getValue().getQuota());
    }

    /**
     * POST /users 缺用户名：@Valid 校验失败，GlobalExceptionHandler 包装为 code=400。
     */
    @Test
    void shouldRejectSaveWithoutUsername() throws Exception {
        mockMvc.perform(post("/jcloud/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"123456\",\"storageSpaceId\":\"s1\",\"quota\":1024}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("用户名不能为空")));
    }

    /**
     * GET /users/{id} 用户不存在：service 抛业务异常，包装为 R（body code=404）。
     */
    @Test
    void shouldWrapNotFoundWhenUserMissing() throws Exception {
        when(userService.getById("u404"))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "用户不存在"));

        mockMvc.perform(get("/jcloud/api/users/u404"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("用户不存在"));
    }
}
