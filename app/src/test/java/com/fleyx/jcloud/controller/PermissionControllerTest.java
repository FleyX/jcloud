package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.common.permission.PermissionRegistry;
import com.fleyx.jcloud.model.vo.PermissionTreeVo;
import com.fleyx.jcloud.model.vo.ResourceVo;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 权限只读控制器测试（权限树查询路由与 R.ok 包装）。
 */
class PermissionControllerTest {

    private final PermissionRegistry permissionRegistry = mock(PermissionRegistry.class);

    private final PermissionController controller = new PermissionController(permissionRegistry);

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    /**
     * GET /jcloud/api/permissions/tree 返回 R.ok 包装的权限树：根节点下挂子节点与资源列表。
     */
    @Test
    void shouldReturnPermissionTree() throws Exception {
        PermissionTreeVo userRead = new PermissionTreeVo();
        userRead.setCode("user:read");
        userRead.setName("用户查询");
        userRead.setParentCode("user");
        userRead.setResources(List.of(new ResourceVo("GET:/jcloud/api/users", "用户查询")));

        PermissionTreeVo user = new PermissionTreeVo();
        user.setCode("user");
        user.setName("用户管理");
        user.setChildren(List.of(userRead));

        when(permissionRegistry.tree()).thenReturn(List.of(user));

        mockMvc.perform(get("/jcloud/api/permissions/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].code").value("user"))
                .andExpect(jsonPath("$.data[0].name").value("用户管理"))
                .andExpect(jsonPath("$.data[0].children[0].code").value("user:read"))
                .andExpect(jsonPath("$.data[0].children[0].resources[0].code").value("GET:/jcloud/api/users"))
                .andExpect(jsonPath("$.data[0].children[0].resources[0].name").value("用户查询"));

        verify(permissionRegistry).tree();
    }

    /**
     * GET /jcloud/api/permissions/tree 权限为空时返回空列表。
     */
    @Test
    void shouldReturnEmptyTree() throws Exception {
        when(permissionRegistry.tree()).thenReturn(List.of());

        mockMvc.perform(get("/jcloud/api/permissions/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(0));

        verify(permissionRegistry).tree();
    }
}
