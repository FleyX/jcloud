package com.fleyx.jcloud.service.support;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fleyx.jcloud.mapper.RoleMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.convert.RoleConvert;
import com.fleyx.jcloud.model.convert.UserConvert;
import com.fleyx.jcloud.model.po.Role;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.po.UserRole;
import com.fleyx.jcloud.model.vo.RoleVo;
import com.fleyx.jcloud.model.vo.UserVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户视图富化支撑组件。
 * <p>
 * 为 UserVo 填充角色列表（含内置超级管理员角色）与存储空间名称。
 */
@Component
@RequiredArgsConstructor
public class UserVoEnrichSupport {

    /** 内置超级管理员角色编码。 */
    public static final String SUPER_ADMIN_ROLE_CODE = "super_admin";

    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final UserConvert userConvert;
    private final RoleConvert roleConvert;

    /**
     * 单个用户视图富化。
     *
     * @param user 用户
     * @return 富化后的 UserVo
     */
    public UserVo enrichUserVo(User user) {
        if (user == null) {
            return null;
        }
        UserVo vo = userConvert.poToVo(user);
        if (vo == null) {
            return null;
        }
        boolean isSuperAdmin = user.isSuperAdmin();
        vo.setIsAdmin(isSuperAdmin);
        List<String> roleIds = userRoleMapper.selectRoleIdsByUserId(vo.getId());
        List<RoleVo> roles = new ArrayList<>();
        if (!roleIds.isEmpty()) {
            roles.addAll(roleConvert.poListToVoList(roleMapper.selectBatchIds(roleIds)));
        }
        if (isSuperAdmin && roles.stream().noneMatch(r -> SUPER_ADMIN_ROLE_CODE.equals(r.getCode()))) {
            roles.add(0, buildSuperAdminRoleVo());
        }
        vo.setRoles(roles);
        return vo;
    }

    /**
     * 批量用户视图富化（角色与空间名称按批查询，避免 N+1）。
     *
     * @param users 用户列表
     * @return 富化后的 UserVo 列表
     */
    public List<UserVo> enrichUserVos(List<User> users) {
        if (CollUtil.isEmpty(users)) {
            return List.of();
        }
        List<UserVo> vos = users.stream().map(userConvert::poToVo).toList();
        List<String> userIds = users.stream().map(User::getId).toList();
        Map<String, Boolean> superAdminMap = users.stream()
                .collect(Collectors.toMap(User::getId, User::isSuperAdmin));

        List<UserRole> userRoles = userRoleMapper.selectByUserIds(userIds);
        Map<String, List<String>> userRoleIdsMap = userRoles.stream()
                .collect(Collectors.groupingBy(UserRole::getUserId,
                        Collectors.mapping(UserRole::getRoleId, Collectors.toList())));
        Set<String> allRoleIds = userRoles.stream()
                .map(UserRole::getRoleId)
                .collect(Collectors.toSet());
        Map<String, Role> roleMap = allRoleIds.isEmpty() ? Map.of()
                : roleMapper.selectBatchIds(new ArrayList<>(allRoleIds)).stream()
                .collect(Collectors.toMap(Role::getId, r -> r));

        Set<String> storageSpaceIds = users.stream()
                .map(User::getStorageSpaceId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
        Map<String, StorageSpace> spaceMap = storageSpaceIds.isEmpty() ? Map.of()
                : storageSpaceMapper.selectBatchIds(new ArrayList<>(storageSpaceIds)).stream()
                .collect(Collectors.toMap(StorageSpace::getId, s -> s));

        for (UserVo vo : vos) {
            String uid = vo.getId();
            boolean isSuperAdmin = superAdminMap.getOrDefault(uid, false);
            vo.setIsAdmin(isSuperAdmin);
            List<String> roleIds = userRoleIdsMap.getOrDefault(uid, List.of());
            List<RoleVo> roles = roleIds.stream()
                    .map(roleMap::get)
                    .filter(Objects::nonNull)
                    .map(roleConvert::poToVo)
                    .collect(Collectors.toList());
            if (isSuperAdmin && roles.stream().noneMatch(r -> SUPER_ADMIN_ROLE_CODE.equals(r.getCode()))) {
                roles.add(0, buildSuperAdminRoleVo());
            }
            vo.setRoles(roles);
            if (StrUtil.isNotBlank(vo.getStorageSpaceId())) {
                StorageSpace space = spaceMap.get(vo.getStorageSpaceId());
                if (space != null) {
                    vo.setStorageSpaceName(space.getName());
                }
            }
        }
        return vos;
    }

    private RoleVo buildSuperAdminRoleVo() {
        RoleVo vo = new RoleVo();
        vo.setCode(SUPER_ADMIN_ROLE_CODE);
        vo.setName("超级管理员");
        vo.setDescription("系统内置超级管理员");
        return vo;
    }
}
