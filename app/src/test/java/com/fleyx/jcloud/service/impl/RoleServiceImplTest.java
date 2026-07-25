package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.RoleMapper;
import com.fleyx.jcloud.mapper.RolePermissionMapper;
import com.fleyx.jcloud.model.dto.RoleSaveDto;
import com.fleyx.jcloud.model.dto.RoleUpdateDto;
import com.fleyx.jcloud.model.po.Role;
import com.fleyx.jcloud.model.vo.RoleVo;
import com.fleyx.jcloud.service.RoleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色服务集成测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RoleServiceImplTest {

    @Autowired
    private RoleService roleService;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String uniqueCode() {
        return "role_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @Test
    void shouldCreateRoleWithoutPermissions() {
        RoleSaveDto dto = new RoleSaveDto();
        dto.setCode(uniqueCode());
        dto.setName("测试角色");
        dto.setDescription("测试角色描述");
        dto.setPermissionCodes(Collections.emptyList());

        RoleVo vo = roleService.saveRole(dto);

        assertNotNull(vo);
        assertNotNull(vo.getId());
        assertEquals(dto.getCode(), vo.getCode());
        assertEquals(dto.getName(), vo.getName());
    }

    @Test
    void shouldCreateRoleWithPermissions() {
        RoleSaveDto dto = new RoleSaveDto();
        dto.setCode(uniqueCode());
        dto.setName("带权限角色");
        dto.setDescription("测试带权限角色");
        dto.setPermissionCodes(List.of("user:menu"));

        RoleVo vo = roleService.saveRole(dto);

        assertNotNull(vo);
        assertNotNull(vo.getId());
        List<String> permissionCodes = rolePermissionMapper.selectPermissionCodesByRoleId(vo.getId());
        assertEquals(1, permissionCodes.size());
        assertEquals("user:menu", permissionCodes.get(0));
    }

    @Test
    void shouldRejectInvalidPermissionCode() {
        RoleSaveDto dto = new RoleSaveDto();
        dto.setCode(uniqueCode());
        dto.setName("非法权限角色");
        dto.setPermissionCodes(List.of("not:exist"));

        BusinessException exception = assertThrows(BusinessException.class, () -> roleService.saveRole(dto));
        assertEquals(ResultCode.PARAM_ERROR, exception.getResultCode());
    }

    @Test
    void shouldRejectDuplicateCode() {
        String code = uniqueCode();
        RoleSaveDto dto = new RoleSaveDto();
        dto.setCode(code);
        dto.setName("测试角色");
        dto.setPermissionCodes(Collections.emptyList());
        roleService.saveRole(dto);

        RoleSaveDto duplicate = new RoleSaveDto();
        duplicate.setCode(code);
        duplicate.setName("重复角色");
        duplicate.setPermissionCodes(Collections.emptyList());

        BusinessException exception = assertThrows(BusinessException.class, () -> roleService.saveRole(duplicate));
        assertEquals(ResultCode.BUSINESS_ERROR, exception.getResultCode());
    }

    @Test
    void shouldNotDeleteSuperAdminRole() {
        Role role = roleMapper.selectOne(new QueryWrapper<Role>().eq("code", "super_admin"));
        assertNotNull(role, "应存在 super_admin 系统角色");

        BusinessException exception = assertThrows(BusinessException.class, () -> roleService.removeById(role.getId()));
        assertEquals(ResultCode.FORBIDDEN, exception.getResultCode());
    }

    @Test
    void shouldNotUpdateSuperAdminRole() {
        Role role = roleMapper.selectOne(new QueryWrapper<Role>().eq("code", "super_admin"));
        assertNotNull(role, "应存在 super_admin 系统角色");

        RoleUpdateDto dto = new RoleUpdateDto();
        dto.setName("修改名称");
        dto.setPermissionCodes(Collections.emptyList());

        BusinessException exception = assertThrows(BusinessException.class, () -> roleService.updateRole(role.getId(), dto));
        assertEquals(ResultCode.FORBIDDEN, exception.getResultCode());
    }

    @Test
    void shouldNotUpdateSuperAdminRoleStatus() {
        Role role = roleMapper.selectOne(new QueryWrapper<Role>().eq("code", "super_admin"));
        assertNotNull(role, "应存在 super_admin 系统角色");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> roleService.updateStatus(role.getId(), 0));
        assertEquals(ResultCode.FORBIDDEN, exception.getResultCode());
    }

    @Test
    void shouldDeleteSystemAdminRole() {
        Role role = roleMapper.selectOne(new QueryWrapper<Role>().eq("code", "system_admin"));
        if (role == null) {
            role = new Role();
            role.setCode("system_admin");
            role.setName("系统管理员");
            role.setDescription("测试系统管理员角色");
            role.setStatus(1);
            roleMapper.insert(role);
        }

        roleService.removeById(role.getId());

        Long deleteAt = jdbcTemplate.queryForObject(
                "SELECT delete_at FROM t_role WHERE id = ?", Long.class, role.getId());
        assertNotNull(deleteAt);
        assertTrue(deleteAt > 0, "删除后 system_admin 的 delete_at 应写入毫秒时间戳");
    }

    @Test
    void shouldWriteDeleteTimestamp() {
        String code = uniqueCode();
        RoleSaveDto dto = new RoleSaveDto();
        dto.setCode(code);
        dto.setName("测试角色");
        dto.setPermissionCodes(Collections.emptyList());
        RoleVo created = roleService.saveRole(dto);

        roleService.removeById(created.getId());

        Long deleteAt = jdbcTemplate.queryForObject(
                "SELECT delete_at FROM t_role WHERE code = ?", Long.class, code);
        assertNotNull(deleteAt);
        assertTrue(deleteAt > 0, "删除后 delete_at 应写入毫秒时间戳");
    }

    @Test
    void shouldAllowReuseCodeAfterDeletion() {
        String code = uniqueCode();
        RoleSaveDto dto = new RoleSaveDto();
        dto.setCode(code);
        dto.setName("测试角色");
        dto.setPermissionCodes(Collections.emptyList());
        RoleVo created = roleService.saveRole(dto);
        roleService.removeById(created.getId());

        RoleSaveDto reused = new RoleSaveDto();
        reused.setCode(code);
        reused.setName("复用角色");
        reused.setPermissionCodes(Collections.emptyList());
        RoleVo vo = roleService.saveRole(reused);

        assertNotNull(vo);
        assertEquals(code, vo.getCode());
    }
}
