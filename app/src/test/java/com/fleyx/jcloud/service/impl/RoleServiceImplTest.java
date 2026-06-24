package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.dto.RoleSaveDto;
import com.fleyx.jcloud.model.vo.RoleVo;
import com.fleyx.jcloud.service.RoleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 角色服务集成测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RoleServiceImplTest {

    @Autowired
    private RoleService roleService;

    private static String uniqueCode() {
        return "role_" + System.nanoTime();
    }

    @Test
    void shouldCreateRoleWithPermissions() {
        RoleSaveDto dto = new RoleSaveDto();
        dto.setCode(uniqueCode());
        dto.setName("测试角色");
        dto.setDescription("测试角色描述");
        dto.setPermissionIds(Collections.emptyList());

        RoleVo vo = roleService.saveRole(dto);

        assertNotNull(vo);
        assertNotNull(vo.getId());
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

        assertThrows(BusinessException.class, () -> roleService.saveRole(duplicate));
    }

    @Test
    void shouldNotDeleteProtectedRole() {
        assertThrows(BusinessException.class, () -> roleService.removeById(1L));
    }
}
