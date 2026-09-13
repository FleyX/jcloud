package com.fleyx.jcloud.common.permission;

import com.fleyx.jcloud.common.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionRegistry} 集成测试：注册开关查询端点必须登记在 permissions.yml 的 public 区，
 * 否则匿名请求会被鉴权过滤器拒绝（ADR-0016）。
 */
class PermissionRegistryPublicResourceTest extends IntegrationTestBase {

    @Autowired
    private PermissionRegistry permissionRegistry;

    @Test
    void registrationEnabledEndpointShouldBePublic() {
        boolean registered = permissionRegistry.filterEntries().stream()
                .anyMatch(entry -> entry.pattern().equals("GET:/jcloud/api/auth/registration-enabled")
                        && PermissionRegistry.TYPE_PUBLIC.equals(entry.type()));
        assertTrue(registered, "GET:/jcloud/api/auth/registration-enabled 必须登记在 permissions.yml 的 public 区");
    }
}
