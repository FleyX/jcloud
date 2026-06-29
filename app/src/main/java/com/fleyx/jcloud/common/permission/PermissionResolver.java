package com.fleyx.jcloud.common.permission;

import cn.hutool.core.collection.CollUtil;
import com.fleyx.jcloud.mapper.PermissionMapper;
import com.fleyx.jcloud.mapper.PermissionResourceMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class PermissionResolver {

    private final PermissionMapper permissionMapper;
    private final PermissionResourceMapper permissionResourceMapper;
    private final UserRoleMapper userRoleMapper;

    /**
     * 根据用户 ID 解析其所有有效权限编码（包含祖先权限）。
     */
    public List<String> resolvePermissionCodes(String userId) {
        List<String> roleIds = CollUtil.emptyIfNull(userRoleMapper.selectRoleIdsByUserId(userId));
        Set<String> permissionIds = resolvePermissionIds(roleIds);
        if (permissionIds.isEmpty()) {
            return List.of();
        }
        return CollUtil.emptyIfNull(permissionMapper.selectCodesByIds(new ArrayList<>(permissionIds)));
    }

    /**
     * 根据角色 ID 列表解析其可访问的资源编码（包含祖先权限资源）。
     */
    public List<String> resolveResourceCodes(List<String> roleIds) {
        Set<String> permissionIds = resolvePermissionIds(CollUtil.emptyIfNull(roleIds));
        if (permissionIds.isEmpty()) {
            return List.of();
        }
        return CollUtil.emptyIfNull(permissionResourceMapper.selectResourceCodesByPermissionIds(new ArrayList<>(permissionIds)));
    }

    /**
     * 根据角色 ID 列表解析其有效权限 ID 集合（包含祖先权限）。
     */
    public Set<String> resolvePermissionIds(List<String> roleIds) {
        if (CollUtil.isEmpty(roleIds)) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        List<String> directIds = CollUtil.emptyIfNull(permissionMapper.selectIdsByRoleIds(roleIds));
        for (String pid : directIds) {
            result.addAll(CollUtil.emptyIfNull(permissionMapper.selectAncestorIds(pid)));
        }
        return result;
    }
}
