package com.fleyx.jcloud.service.support;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.cache.UserPermissionCache;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.UserStatus;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.RoleMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.dto.UserStatusDto;
import com.fleyx.jcloud.model.dto.UserUpdateRolesDto;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.po.UserRole;
import com.fleyx.jcloud.util.IdUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户角色与状态支撑组件。
 * <p>
 * 收敛角色分配、状态变更以及内置/超级管理员守卫逻辑。
 */
@Component
@RequiredArgsConstructor
public class UserRoleSupport {

    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserPermissionCache userPermissionCache;
    private final UserSpaceSupport userSpaceSupport;
    private final UserMapper userMapper;

    /**
     * 全量更新用户角色。
     *
     * @param dto 用户 ID 与角色 ID 列表
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateRoles(UserUpdateRolesDto dto) {
        User user = userSpaceSupport.requireUser(dto.getUserId());
        rejectIfBuiltInAdmin(user, "不能修改内置管理员的角色");
        validateRoleIds(dto.getRoleIds());
        replaceUserRoles(user.getId(), dto.getRoleIds());
        userPermissionCache.evict(user.getId());
    }

    /**
     * 更新用户状态（启用/禁用）。
     *
     * @param dto 用户 ID 与目标状态
     */
    public void updateStatus(UserStatusDto dto) {
        User user = userSpaceSupport.requireUser(dto.getUserId());
        rejectIfBuiltInAdmin(user, "不能禁用/启用内置管理员账号");
        validateStatus(dto.getStatus());
        User update = new User();
        update.setId(user.getId());
        update.setStatus(dto.getStatus());
        userMapper.updateById(update);
    }

    /**
     * 全量替换用户角色（不驱逐权限缓存，由调用方负责）。
     *
     * @param userId  用户 ID
     * @param roleIds 角色 ID 列表
     */
    public void replaceUserRoles(String userId, List<String> roleIds) {
        LambdaQueryWrapper<UserRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserRole::getUserId, userId);
        userRoleMapper.delete(wrapper);
        if (CollUtil.isEmpty(roleIds)) {
            return;
        }
        List<UserRole> relations = roleIds.stream()
                .map(roleId -> buildUserRole(userId, roleId))
                .collect(Collectors.toList());
        userRoleMapper.batchInsert(relations);
    }

    /**
     * 校验角色 ID 全部有效。
     *
     * @param roleIds 角色 ID 列表，空则跳过
     */
    public void validateRoleIds(List<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        long validCount = roleMapper.selectBatchIds(roleIds).size();
        if (validCount != roleIds.size()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "存在无效的角色 ID");
        }
    }

    /**
     * 校验用户状态值合法。
     *
     * @param status 状态值，null 跳过
     */
    public void validateStatus(Integer status) {
        if (status == null) {
            return;
        }
        if (status != UserStatus.ENABLED.getCode() && status != UserStatus.DISABLED.getCode()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户状态只能是 1（启用）或 0（禁用）");
        }
    }

    /**
     * 超级管理员拦截。
     *
     * @param user    用户
     * @param message 拦截消息
     */
    public void rejectIfSuperAdmin(User user, String message) {
        if (user.isSuperAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN, message);
        }
    }

    /**
     * 内置管理员拦截。
     *
     * @param user    用户
     * @param message 拦截消息
     */
    public void rejectIfBuiltInAdmin(User user, String message) {
        if (isBuiltInAdmin(user)) {
            throw new BusinessException(ResultCode.FORBIDDEN, message);
        }
    }

    /**
     * 是否内置管理员账号。
     *
     * @param user 用户
     * @return true 表示内置管理员
     */
    public boolean isBuiltInAdmin(User user) {
        return "admin".equals(user.getUsername());
    }

    private UserRole buildUserRole(String userId, String roleId) {
        UserRole relation = new UserRole();
        relation.setId(IdUtil.nextId());
        relation.setUserId(userId);
        relation.setRoleId(roleId);
        relation.setCreateTime(LocalDateTime.now());
        return relation;
    }
}
