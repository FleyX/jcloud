package com.fleyx.jcloud.service.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.NotificationTargetType;
import com.fleyx.jcloud.common.enums.UserStatus;
import com.fleyx.jcloud.common.event.NotificationEvent;
import com.fleyx.jcloud.mapper.RoleMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.po.Role;
import com.fleyx.jcloud.model.po.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通知收件人解析支撑类（ADR 0039）。
 * <p>
 * 用户级事件取单个用户；管理员广播取全部持有超级管理员角色且状态启用的用户。
 */
@Component
@RequiredArgsConstructor
public class NotificationRecipientSupport {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;

    /**
     * 解析事件的收件用户集合。
     *
     * @param event 通知事件
     * @return 收件用户列表，无人可收时为空列表
     */
    public List<User> resolve(NotificationEvent event) {
        if (NotificationTargetType.ADMINS == event.getTargetType()) {
            return resolveAdmins();
        }
        return resolveUser(event.getTargetUserId());
    }

    private List<User> resolveUser(String userId) {
        if (StrUtil.isBlank(userId)) {
            return List.of();
        }
        User user = userMapper.selectById(userId);
        return user == null ? List.of() : List.of(user);
    }

    private List<User> resolveAdmins() {
        Role superAdminRole = roleMapper.selectOne(new LambdaQueryWrapper<Role>()
                .eq(Role::getCode, UserVoEnrichSupport.SUPER_ADMIN_ROLE_CODE)
                .last("LIMIT 1"));
        if (superAdminRole == null) {
            return List.of();
        }
        List<String> userIds = userRoleMapper.selectUserIdsByRoleId(superAdminRole.getId());
        if (userIds.isEmpty()) {
            return List.of();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .filter(user -> Integer.valueOf(UserStatus.ENABLED.getCode()).equals(user.getStatus()))
                .toList();
    }
}
