package com.fleyx.jcloud.common.permission;

import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.mapper.RoleMapper;
import com.fleyx.jcloud.mapper.RolePermissionMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.po.Role;
import com.fleyx.jcloud.model.po.RolePermission;
import com.fleyx.jcloud.model.po.UserRole;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionResolver} 集成测试：真实 DB 绑定角色/权限后验证资源码解析（含祖先权限资源、未知码静默忽略、多角色并集）。
 */
@Transactional
class PermissionResolverTest extends IntegrationTestBase {

    @Autowired
    private PermissionResolver permissionResolver;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private PermissionRegistry permissionRegistry;

    @Test
    void shouldReturnEmptyForNullOrEmptyRoleIds() {
        assertTrue(permissionResolver.resolveResourceCodes(null).isEmpty());
        assertTrue(permissionResolver.resolveResourceCodes(List.of()).isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenUserHasNoRoles() {
        UserVo user = prepareUserWithStorageSpace().user();
        assertTrue(userRoleMapper.selectRoleIdsByUserId(user.getId()).isEmpty(), "新用户不应有角色");

        List<String> codes = permissionResolver.resolveResourceCodesByUserId(user.getId());

        assertTrue(codes.isEmpty());
    }

    @Test
    void shouldResolveResourceCodesIncludingAncestorResources() {
        assertTrue(permissionRegistry.containsPermission("user:menu"), "权限码 user:menu 必须存在于 permissions.yml");
        assertTrue(permissionRegistry.containsPermission("system:menu"), "权限码 system:menu 必须存在于 permissions.yml");

        UserVo user = prepareUserWithStorageSpace().user();
        Role role = createRole();
        bindPermission(role.getId(), "user:menu");
        bindRoleToUser(user.getId(), role.getId());

        List<String> codes = permissionResolver.resolveResourceCodesByUserId(user.getId());

        // 子权限 user:menu 自身资源
        assertTrue(codes.contains("VIEW:/admin/users"));
        assertTrue(codes.contains("PUT:/jcloud/api/users/{id}/roles"));
        // 祖先权限 system:menu 的资源
        assertTrue(codes.contains("VIEW:/admin"));
        assertTrue(codes.contains("POST:/jcloud/api/admin/users/{id}/migrate"));
        // 展开结果无重复
        assertEquals(codes.stream().distinct().count(), (long) codes.size());

        // 按角色 ID 列表解析与按用户解析结果一致
        assertEquals(codes, permissionResolver.resolveResourceCodes(List.of(role.getId())));
    }

    @Test
    void shouldSilentlyIgnoreUnknownPermissionCode() {
        UserVo user = prepareUserWithStorageSpace().user();
        Role role = createRole();
        bindPermission(role.getId(), "ghost:menu");
        bindPermission(role.getId(), "note:menu");
        bindRoleToUser(user.getId(), role.getId());

        List<String> codes = permissionResolver.resolveResourceCodesByUserId(user.getId());

        // 不存在的权限码被静默忽略，真实码 note:menu 仍正常解析
        assertEquals(List.of("VIEW:/notes"), codes);
    }

    @Test
    void shouldUnionResourcesAcrossMultipleRoles() {
        UserVo user = prepareUserWithStorageSpace().user();
        Role noteRole = createRole();
        bindPermission(noteRole.getId(), "note:menu");
        Role todoRole = createRole();
        bindPermission(todoRole.getId(), "todo:menu");
        bindRoleToUser(user.getId(), noteRole.getId());
        bindRoleToUser(user.getId(), todoRole.getId());

        List<String> codes = permissionResolver.resolveResourceCodesByUserId(user.getId());

        assertEquals(2, codes.size());
        assertTrue(codes.contains("VIEW:/notes"));
        assertTrue(codes.contains("VIEW:/todos"));
    }

    private Role createRole() {
        Role role = new Role();
        role.setCode("test_role_" + Long.toUnsignedString(System.nanoTime(), 36));
        role.setName("测试角色");
        role.setStatus(1);
        roleMapper.insert(role);
        return role;
    }

    private void bindPermission(String roleId, String permissionCode) {
        RolePermission rp = new RolePermission();
        rp.setRoleId(roleId);
        rp.setPermissionCode(permissionCode);
        rolePermissionMapper.insert(rp);
    }

    private void bindRoleToUser(String userId, String roleId) {
        UserRole ur = new UserRole();
        ur.setUserId(userId);
        ur.setRoleId(roleId);
        userRoleMapper.insert(ur);
    }
}
