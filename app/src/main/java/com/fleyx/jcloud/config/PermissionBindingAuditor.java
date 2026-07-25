package com.fleyx.jcloud.config;

import com.fleyx.jcloud.common.permission.PermissionRegistry;
import com.fleyx.jcloud.mapper.RolePermissionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 角色权限绑定审计。
 * <p>
 * 启动时检查 t_role_permission 中是否存在 permissions.yml 里已不存在的权限编码，
 * 存在则打印 warn 日志提醒管理员到角色管理中清理（不自动修改用户数据）。
 */
@Slf4j
@Component
@Order(200)
@RequiredArgsConstructor
public class PermissionBindingAuditor implements ApplicationRunner {

    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionRegistry permissionRegistry;

    @Override
    public void run(ApplicationArguments args) {
        List<String> unknown = permissionRegistry.findUnknownCodes(rolePermissionMapper.selectDistinctPermissionCodes());
        if (!unknown.isEmpty()) {
            log.warn("检测到角色绑定了已不存在的权限编码 {}，这些绑定不会生效，请到角色管理中清理", unknown);
        }
    }
}
