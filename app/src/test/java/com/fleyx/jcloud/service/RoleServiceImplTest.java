package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.vo.RoleVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 角色服务单元测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RoleServiceImplTest {

    @Autowired
    private RoleService roleService;

    @Test
    void listAllShouldReturnRoles() {
        List<RoleVo> roles = roleService.listAll();
        assertNotNull(roles);
        assertFalse(roles.isEmpty());
    }
}
