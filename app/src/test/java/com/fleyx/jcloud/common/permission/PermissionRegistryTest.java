package com.fleyx.jcloud.common.permission;

import com.fleyx.jcloud.model.vo.PermissionTreeVo;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionRegistry} 单元测试。
 * <p>
 * 验证 YAML 加载、各类非法配置的 fail-fast 校验以及资源解析逻辑。
 */
class PermissionRegistryTest {

    private static PermissionRegistry registryOf(String yaml) {
        return new PermissionRegistry(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shouldLoadProductionYaml() {
        PermissionRegistry registry = new PermissionRegistry(new ClassPathResource("permissions.yml"));

        assertTrue(registry.containsPermission("file:menu"));
        assertTrue(registry.containsPermission("user:menu"));
        assertFalse(registry.containsPermission("not:exist"));
        assertFalse(registry.filterEntries().isEmpty());
        assertTrue(registry.allResourceCodes().contains("VIEW:/files"));
        assertTrue(registry.allResourceCodes().contains("GET:/jcloud/api/files"));
    }

    @Test
    void shouldResolveResourceCodesWithAncestors() {
        PermissionRegistry registry = new PermissionRegistry(new ClassPathResource("permissions.yml"));

        // 绑定子权限应同时获得祖先权限的资源
        List<String> codes = registry.resolveResourceCodes(List.of("user:menu"));
        assertTrue(codes.contains("VIEW:/admin/users"));
        assertTrue(codes.contains("GET:/jcloud/api/users"));
        assertTrue(codes.contains("VIEW:/admin"), "应包含祖先 system:menu 的资源");

        // 不存在的编码静默忽略
        assertTrue(registry.resolveResourceCodes(List.of("not:exist")).isEmpty());
        assertTrue(registry.resolveResourceCodes(null).isEmpty());
    }

    @Test
    void shouldBuildTree() {
        PermissionRegistry registry = new PermissionRegistry(new ClassPathResource("permissions.yml"));

        List<PermissionTreeVo> roots = registry.tree();
        List<String> rootCodes = roots.stream().map(PermissionTreeVo::getCode).toList();
        assertTrue(rootCodes.contains("system:menu"));
        assertFalse(rootCodes.contains("user:menu"), "子权限不应出现在根节点");

        PermissionTreeVo system = roots.stream().filter(r -> "system:menu".equals(r.getCode())).findFirst().orElseThrow();
        List<String> childCodes = system.getChildren().stream().map(PermissionTreeVo::getCode).toList();
        assertTrue(childCodes.contains("user:menu"));
        assertTrue(childCodes.contains("role:menu"));
        assertFalse(system.getResources().isEmpty());
    }

    @Test
    void shouldExpandWithAncestors() {
        PermissionRegistry registry = new PermissionRegistry(new ClassPathResource("permissions.yml"));

        Set<String> expanded = registry.expandWithAncestors(List.of("user:menu"));
        assertEquals(Set.of("user:menu", "system:menu"), expanded);
    }

    @Test
    void shouldRejectDuplicatePermissionCode() {
        String yaml = """
                permissions:
                  - { code: "a:menu", name: "A" }
                  - { code: "a:menu", name: "A2" }
                """;
        assertThrows(IllegalStateException.class, () -> registryOf(yaml));
    }

    @Test
    void shouldRejectDuplicateResourceCode() {
        String yaml = """
                public:
                  - { code: "GET:/x", name: "x" }
                permissions:
                  - code: "a:menu"
                    name: "A"
                    resources:
                      - { code: "GET:/x", name: "x2" }
                """;
        assertThrows(IllegalStateException.class, () -> registryOf(yaml));
    }

    @Test
    void shouldRejectDanglingParent() {
        String yaml = """
                permissions:
                  - { code: "a:menu", name: "A", parent: "not:exist" }
                """;
        assertThrows(IllegalStateException.class, () -> registryOf(yaml));
    }

    @Test
    void shouldRejectCycle() {
        String yaml = """
                permissions:
                  - { code: "a:menu", name: "A", parent: "b:menu" }
                  - { code: "b:menu", name: "B", parent: "a:menu" }
                """;
        assertThrows(IllegalStateException.class, () -> registryOf(yaml));
    }

    @Test
    void shouldRejectInvalidResourceCodeFormat() {
        String yaml = """
                public:
                  - { code: "/jcloud/api/no-method", name: "缺少方法前缀" }
                """;
        assertThrows(IllegalStateException.class, () -> registryOf(yaml));
    }

    @Test
    void shouldFindUnknownCodes() {
        PermissionRegistry registry = new PermissionRegistry(new ClassPathResource("permissions.yml"));

        assertEquals(List.of("not:exist"), registry.findUnknownCodes(List.of("file:menu", "not:exist")));
        assertTrue(registry.findUnknownCodes(null).isEmpty());
    }
}
