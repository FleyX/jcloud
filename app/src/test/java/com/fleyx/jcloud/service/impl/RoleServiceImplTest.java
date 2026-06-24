package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.RoleMapper;
import com.fleyx.jcloud.model.dto.RoleSaveDto;
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
        dto.setPermissionIds(Collections.emptyList());

        RoleVo vo = roleService.saveRole(dto);

        assertNotNull(vo);
        assertNotNull(vo.getId());
        assertEquals(dto.getCode(), vo.getCode());
        assertEquals(dto.getName(), vo.getName());
    }

    @Test
    void shouldRejectDuplicateCode() {
        String code = uniqueCode();
        RoleSaveDto dto = new RoleSaveDto();
        dto.setCode(code);
        dto.setName("测试角色");
        dto.setPermissionIds(Collections.emptyList());
        roleService.saveRole(dto);

        RoleSaveDto duplicate = new RoleSaveDto();
        duplicate.setCode(code);
        duplicate.setName("重复角色");
        duplicate.setPermissionIds(Collections.emptyList());

        BusinessException exception = assertThrows(BusinessException.class, () -> roleService.saveRole(duplicate));
        assertEquals(ResultCode.BUSINESS_ERROR, exception.getResultCode());
    }

    @Test
    void shouldNotDeleteProtectedRole() {
        Role role = roleMapper.selectOne(new QueryWrapper<Role>().eq("code", "super_admin"));
        assertNotNull(role, "应存在 super_admin 系统角色");

        BusinessException exception = assertThrows(BusinessException.class, () -> roleService.removeById(role.getId()));
        assertEquals(ResultCode.FORBIDDEN, exception.getResultCode());
    }

    @Test
    void shouldWriteDeleteTimestamp() {
        String code = uniqueCode();
        RoleSaveDto dto = new RoleSaveDto();
        dto.setCode(code);
        dto.setName("测试角色");
        dto.setPermissionIds(Collections.emptyList());
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
        dto.setPermissionIds(Collections.emptyList());
        RoleVo created = roleService.saveRole(dto);
        roleService.removeById(created.getId());

        RoleSaveDto reused = new RoleSaveDto();
        reused.setCode(code);
        reused.setName("复用角色");
        reused.setPermissionIds(Collections.emptyList());
        RoleVo vo = roleService.saveRole(reused);

        assertNotNull(vo);
        assertEquals(code, vo.getCode());
    }
}
