package com.fleyx.jcloud.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.model.dto.RolePageQueryDto;
import com.fleyx.jcloud.model.dto.RoleSaveDto;
import com.fleyx.jcloud.model.dto.RoleUpdateDto;
import com.fleyx.jcloud.model.vo.RoleVo;
import com.fleyx.jcloud.service.RoleService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 角色管理控制器测试（CRUD、角色-权限绑定 DTO 透传与校验包装）。
 */
class RoleControllerTest {

    private final RoleService roleService = mock(RoleService.class);

    private final RoleController controller = new RoleController(roleService);

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    /**
     * GET /jcloud/api/roles 查询所有启用角色，返回 R.ok 包装的角色列表。
     */
    @Test
    void shouldListAllEnabledRoles() throws Exception {
        RoleVo vo = new RoleVo();
        vo.setId("r1");
        vo.setCode("admin");
        vo.setName("管理员");
        vo.setStatus(1);
        when(roleService.listAllEnabled()).thenReturn(List.of(vo));

        mockMvc.perform(get("/jcloud/api/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value("r1"))
                .andExpect(jsonPath("$.data[0].code").value("admin"))
                .andExpect(jsonPath("$.data[0].name").value("管理员"))
                .andExpect(jsonPath("$.data[0].status").value(1));

        verify(roleService).listAllEnabled();
    }

    /**
     * GET /jcloud/api/roles/page 分页查询：code/name/status/pageNum/pageSize 绑定到 DTO 并透传 service。
     */
    @Test
    void shouldPageRolesWithQueryParams() throws Exception {
        RoleVo vo = new RoleVo();
        vo.setId("r1");
        vo.setCode("admin");
        Page<RoleVo> page = new Page<>(2, 10, 1);
        page.setRecords(List.of(vo));
        when(roleService.pageRoles(any(RolePageQueryDto.class))).thenReturn(page);

        mockMvc.perform(get("/jcloud/api/roles/page")
                        .param("code", "admin")
                        .param("name", "管理")
                        .param("status", "1")
                        .param("pageNum", "2")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].id").value("r1"))
                .andExpect(jsonPath("$.data.records[0].code").value("admin"));

        ArgumentCaptor<RolePageQueryDto> captor = ArgumentCaptor.forClass(RolePageQueryDto.class);
        verify(roleService).pageRoles(captor.capture());
        RolePageQueryDto dto = captor.getValue();
        assertEquals("admin", dto.getCode());
        assertEquals("管理", dto.getName());
        assertEquals(1, dto.getStatus());
        assertEquals(2L, dto.getPageNum());
        assertEquals(10L, dto.getPageSize());
    }

    /**
     * GET /jcloud/api/roles/{id} 根据 ID 查询角色，返回 R.ok 包装的角色视图。
     */
    @Test
    void shouldGetRoleById() throws Exception {
        RoleVo vo = new RoleVo();
        vo.setId("r1");
        vo.setCode("admin");
        when(roleService.getById("r1")).thenReturn(vo);

        mockMvc.perform(get("/jcloud/api/roles/r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("r1"))
                .andExpect(jsonPath("$.data.code").value("admin"));

        verify(roleService).getById(eq("r1"));
    }

    /**
     * POST /jcloud/api/roles 新增角色：code/name/status/permissionCodes 反序列化后整体透传 service。
     */
    @Test
    void shouldSaveRoleWithPermissionCodes() throws Exception {
        RoleVo vo = new RoleVo();
        vo.setId("r1");
        vo.setCode("admin");
        when(roleService.saveRole(any(RoleSaveDto.class))).thenReturn(vo);

        mockMvc.perform(post("/jcloud/api/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"admin\",\"name\":\"管理员\",\"status\":1,"
                                + "\"permissionCodes\":[\"user:read\",\"user:write\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("r1"))
                .andExpect(jsonPath("$.data.code").value("admin"));

        ArgumentCaptor<RoleSaveDto> captor = ArgumentCaptor.forClass(RoleSaveDto.class);
        verify(roleService).saveRole(captor.capture());
        RoleSaveDto dto = captor.getValue();
        assertEquals("admin", dto.getCode());
        assertEquals("管理员", dto.getName());
        assertEquals(1, dto.getStatus());
        assertEquals(List.of("user:read", "user:write"), dto.getPermissionCodes());
    }

    /**
     * PUT /jcloud/api/roles/{id} 更新角色：路径 ID 与 permissionCodes 等更新 DTO 透传 service。
     */
    @Test
    void shouldUpdateRoleWithPermissionCodes() throws Exception {
        RoleVo vo = new RoleVo();
        vo.setId("r1");
        vo.setCode("admin");
        when(roleService.updateRole(eq("r1"), any(RoleUpdateDto.class))).thenReturn(vo);

        mockMvc.perform(put("/jcloud/api/roles/r1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"超级管理员\",\"status\":0,\"permissionCodes\":[\"user:read\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("r1"));

        ArgumentCaptor<RoleUpdateDto> captor = ArgumentCaptor.forClass(RoleUpdateDto.class);
        verify(roleService).updateRole(eq("r1"), captor.capture());
        RoleUpdateDto dto = captor.getValue();
        assertEquals("超级管理员", dto.getName());
        assertEquals(0, dto.getStatus());
        assertEquals(List.of("user:read"), dto.getPermissionCodes());
    }

    /**
     * DELETE /jcloud/api/roles/{id} 删除角色：路径 ID 透传，返回 R.ok 空数据。
     */
    @Test
    void shouldDeleteRole() throws Exception {
        mockMvc.perform(delete("/jcloud/api/roles/r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(roleService).removeById(eq("r1"));
    }

    /**
     * PATCH /jcloud/api/roles/{id}/status 修改角色状态：路径 ID 与请求体 status 透传。
     */
    @Test
    void shouldUpdateRoleStatus() throws Exception {
        mockMvc.perform(patch("/jcloud/api/roles/r1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(roleService).updateStatus(eq("r1"), eq(0));
    }

    /**
     * POST /jcloud/api/roles 缺必填字段：@Valid 校验失败，GlobalExceptionHandler 包装为 code=400。
     */
    @Test
    void shouldRejectSaveWithoutRequiredFields() throws Exception {
        mockMvc.perform(post("/jcloud/api/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("角色编码不能为空")));
    }

    /**
     * GET /jcloud/api/roles/{id} 角色不存在：service 抛业务异常，包装为 R（body code=404）。
     */
    @Test
    void shouldWrapNotFoundWhenRoleMissing() throws Exception {
        when(roleService.getById("r404"))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "角色不存在"));

        mockMvc.perform(get("/jcloud/api/roles/r404"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("角色不存在"));
    }
}
