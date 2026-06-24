package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.enums.CommonStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.dto.PermissionSaveDto;
import com.fleyx.jcloud.model.dto.PermissionUpdateDto;
import com.fleyx.jcloud.model.vo.PermissionTreeVo;
import com.fleyx.jcloud.model.vo.PermissionVo;
import com.fleyx.jcloud.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限服务集成测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PermissionServiceImplTest {

    @Autowired
    private PermissionService permissionService;

    private static String uniqueCode() {
        return "perm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @Test
    void shouldCreateTopLevelPermission() {
        PermissionSaveDto dto = new PermissionSaveDto();
        dto.setCode(uniqueCode());
        dto.setName("测试顶级权限");
        dto.setParentId(null);
        dto.setResourceIds(Collections.emptyList());

        PermissionVo vo = permissionService.savePermission(dto);

        assertNotNull(vo);
        assertNotNull(vo.getId());
        assertEquals(dto.getCode(), vo.getCode());
        assertEquals(dto.getName(), vo.getName());
        assertNull(vo.getParentId(), "顶级权限的 parentId 应为 null");
    }

    @Test
    void shouldRejectCycle() {
        PermissionSaveDto parentDto = new PermissionSaveDto();
        parentDto.setCode(uniqueCode());
        parentDto.setName("权限A");
        parentDto.setParentId(null);
        parentDto.setResourceIds(Collections.emptyList());
        PermissionVo parent = permissionService.savePermission(parentDto);

        PermissionSaveDto childDto = new PermissionSaveDto();
        childDto.setCode(uniqueCode());
        childDto.setName("权限B");
        childDto.setParentId(parent.getId());
        childDto.setResourceIds(Collections.emptyList());
        PermissionVo child = permissionService.savePermission(childDto);

        PermissionUpdateDto updateDto = new PermissionUpdateDto();
        updateDto.setName(parent.getName());
        updateDto.setStatus(CommonStatus.ENABLED.getCode());
        updateDto.setParentId(child.getId());
        updateDto.setResourceIds(Collections.emptyList());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> permissionService.updatePermission(parent.getId(), updateDto));
        assertEquals(ResultCode.PARAM_ERROR, exception.getResultCode());
    }

    @Test
    void treeShouldContainSystemMenus() {
        List<PermissionTreeVo> tree = permissionService.tree();

        assertNotNull(tree);
        assertTrue(tree.stream().anyMatch(vo -> "system:menu".equals(vo.getCode())),
                "权限树中应包含 system:menu");
    }
}
