package com.fleyx.jcloud.common.permission;

import cn.hutool.core.collection.CollUtil;
import com.fleyx.jcloud.mapper.RolePermissionMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 权限解析器。
 * <p>
 * 将用户/角色绑定的权限编码解析为资源编码（含祖先权限的资源），
 * 权限与资源数据来自内存 PermissionRegistry，不存在的编码静默忽略。
 */
@Component
@RequiredArgsConstructor
public class PermissionResolver {

    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final PermissionRegistry permissionRegistry;

    /**
     * 根据用户 ID 解析其可访问的资源编码（含祖先权限资源）。
     */
    public List<String> resolveResourceCodesByUserId(String userId) {
        List<String> roleIds = CollUtil.emptyIfNull(userRoleMapper.selectRoleIdsByUserId(userId));
        return resolveResourceCodes(roleIds);
    }

    /**
     * 根据角色 ID 列表解析其可访问的资源编码（含祖先权限资源）。
     */
    public List<String> resolveResourceCodes(List<String> roleIds) {
        if (CollUtil.isEmpty(roleIds)) {
            return List.of();
        }
        List<String> boundCodes = rolePermissionMapper.selectPermissionCodesByRoleIds(roleIds);
        return permissionRegistry.resolveResourceCodes(boundCodes);
    }
}
