package com.fleyx.jcloud.config;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.UserStatus;
import com.fleyx.jcloud.mapper.RoleMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.po.Role;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.po.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 系统启动时初始化超级管理员账号。
 * 若 admin 账号不存在，则自动生成强密码并打印到后端日志。
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class AdminInitializer implements ApplicationRunner {

    private static final String ADMIN_USERNAME = "admin";
    private static final String SUPER_ADMIN_ROLE_CODE = "super_admin";
    private static final String SUPER_ADMIN_ROLE_NAME = "超级管理员";
    private static final String DEV_TEST_PASSWORD = "admin";
    private static final int ADMIN_PASSWORD_LENGTH = 16;
    private static final List<String> FIXED_PASSWORD_PROFILES = Arrays.asList("dev", "test");

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, ADMIN_USERNAME);
        User admin = userMapper.selectOne(wrapper);

        if (admin == null) {
            String rawPassword = useFixedPassword() ? DEV_TEST_PASSWORD : RandomUtil.randomString(ADMIN_PASSWORD_LENGTH);
            admin = new User();
            admin.setUsername(ADMIN_USERNAME);
            admin.setPassword(BCrypt.hashpw(rawPassword, BCrypt.gensalt()));
            admin.setNickname("超级管理员");
            admin.setStatus(UserStatus.ENABLED.getCode());
            admin.setIsAdmin(1);
            userMapper.insert(admin);

            log.warn("=================================================================");
            log.warn("  超级管理员账号初始化完成");
            log.warn("  用户名：{}", ADMIN_USERNAME);
            log.warn("  初始密码：{}", rawPassword);
            log.warn("  请登录后尽快修改密码并妥善保存。");
            log.warn("=================================================================");
        } else {
            log.info("超级管理员账号 {} 已存在，跳过创建", ADMIN_USERNAME);
        }

        Role superAdminRole = ensureSuperAdminRole();
        bindRoleIfAbsent(admin.getId(), superAdminRole.getId());
    }

    private Role ensureSuperAdminRole() {
        LambdaQueryWrapper<Role> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Role::getCode, SUPER_ADMIN_ROLE_CODE);
        Role role = roleMapper.selectOne(wrapper);
        if (role != null) {
            return role;
        }
        role = new Role();
        role.setCode(SUPER_ADMIN_ROLE_CODE);
        role.setName(SUPER_ADMIN_ROLE_NAME);
        role.setDescription("系统内置超级管理员，拥有所有权限且不可删除");
        role.setStatus(1);
        roleMapper.insert(role);
        return role;
    }

    private void bindRoleIfAbsent(String userId, String roleId) {
        LambdaQueryWrapper<UserRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserRole::getUserId, userId).eq(UserRole::getRoleId, roleId);
        if (userRoleMapper.selectCount(wrapper) > 0) {
            return;
        }
        UserRole relation = new UserRole();
        relation.setUserId(userId);
        relation.setRoleId(roleId);
        userRoleMapper.insert(relation);
    }

    private boolean useFixedPassword() {
        if (environment == null) {
            return false;
        }
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(FIXED_PASSWORD_PROFILES::contains);
    }
}
