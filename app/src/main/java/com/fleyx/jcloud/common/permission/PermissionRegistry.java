package com.fleyx.jcloud.common.permission;

import com.fleyx.jcloud.model.vo.PermissionTreeVo;
import com.fleyx.jcloud.model.vo.ResourceVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 权限注册表。
 * <p>
 * 启动时从 classpath 的 permissions.yml 加载权限与资源定义并构建不可变结构，
 * 是权限/资源数据的唯一来源（数据库不再存储）。配置非法时抛出异常使应用启动失败。
 */
@Component
public class PermissionRegistry {

    /** 资源类型：无需登录。 */
    public static final String TYPE_PUBLIC = "PUBLIC";

    /** 资源类型：仅需登录。 */
    public static final String TYPE_LOGIN = "LOGIN";

    /** 前端资源 code 前缀。 */
    public static final String VIEW_PREFIX = "VIEW:";

    private static final Pattern API_CODE_PATTERN =
            Pattern.compile("^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS):/\\S*$");

    /**
     * 资源定义。
     *
     * @param code 资源编码（API 资源为 METHOD:path，前端资源以 VIEW: 为前缀）
     * @param name 资源名称（仅人读）
     */
    public record ResourceDef(String code, String name) {
    }

    /**
     * 权限定义。
     *
     * @param code       权限编码
     * @param name       权限名称
     * @param parentCode 父权限编码，无则为 null
     * @param resources  权限下挂的资源列表
     */
    public record PermissionDef(String code, String name, String parentCode, List<ResourceDef> resources) {
    }

    /**
     * 过滤器资源条目。
     *
     * @param pattern 匹配模式（METHOD:path，Ant 风格）
     * @param type    资源类型：PUBLIC / LOGIN
     */
    public record FilterResourceEntry(String pattern, String type) {
    }

    private final List<ResourceDef> publicResources;
    private final List<ResourceDef> loginResources;
    private final Map<String, PermissionDef> permissions;

    public PermissionRegistry(@Value("classpath:permissions.yml") Resource resource) {
        Map<String, Object> root = loadYaml(resource);
        this.publicResources = parseResources(root.get("public"), "public");
        this.loginResources = parseResources(root.get("login"), "login");
        this.permissions = parsePermissions(root.get("permissions"));
        validate();
    }

    /**
     * 过滤器使用的资源条目（仅 PUBLIC 与 LOGIN）。
     *
     * @return 资源条目列表
     */
    public List<FilterResourceEntry> filterEntries() {
        List<FilterResourceEntry> entries = new ArrayList<>();
        publicResources.forEach(r -> entries.add(new FilterResourceEntry(r.code(), TYPE_PUBLIC)));
        loginResources.forEach(r -> entries.add(new FilterResourceEntry(r.code(), TYPE_LOGIN)));
        return entries;
    }

    /**
     * 判断权限编码是否存在。
     *
     * @param code 权限编码
     * @return true 表示存在
     */
    public boolean containsPermission(String code) {
        return permissions.containsKey(code);
    }

    /**
     * 将绑定的权限编码集合展开为含祖先权限的编码集合，忽略不存在的编码。
     *
     * @param boundCodes 角色绑定的权限编码
     * @return 展开后的权限编码集合（含祖先）
     */
    public Set<String> expandWithAncestors(Collection<String> boundCodes) {
        Set<String> result = new HashSet<>();
        if (boundCodes == null) {
            return result;
        }
        for (String code : boundCodes) {
            String current = code;
            while (current != null && result.add(current)) {
                PermissionDef def = permissions.get(current);
                current = def == null ? null : def.parentCode();
            }
        }
        return result;
    }

    /**
     * 解析绑定的权限编码集合对应的全部资源编码（含祖先权限的资源，忽略不存在的编码）。
     *
     * @param boundCodes 角色绑定的权限编码
     * @return 资源编码列表
     */
    public List<String> resolveResourceCodes(Collection<String> boundCodes) {
        List<String> codes = new ArrayList<>();
        for (String code : expandWithAncestors(boundCodes)) {
            PermissionDef def = permissions.get(code);
            if (def != null) {
                def.resources().forEach(r -> codes.add(r.code()));
            }
        }
        return codes;
    }

    /**
     * 全部资源编码（用于超级管理员）。
     *
     * @return 全量资源编码列表
     */
    public List<String> allResourceCodes() {
        List<String> codes = new ArrayList<>();
        publicResources.forEach(r -> codes.add(r.code()));
        loginResources.forEach(r -> codes.add(r.code()));
        permissions.values().forEach(p -> p.resources().forEach(r -> codes.add(r.code())));
        return codes;
    }

    /**
     * 构建只读权限树（供角色分配界面使用）。
     *
     * @return 权限树根节点列表
     */
    public List<PermissionTreeVo> tree() {
        Map<String, PermissionTreeVo> nodeMap = new LinkedHashMap<>();
        permissions.values().forEach(p -> nodeMap.put(p.code(), toTreeNode(p)));
        List<PermissionTreeVo> roots = new ArrayList<>();
        for (PermissionDef def : permissions.values()) {
            PermissionTreeVo node = nodeMap.get(def.code());
            if (def.parentCode() == null) {
                roots.add(node);
            } else {
                PermissionTreeVo parent = nodeMap.get(def.parentCode());
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    private PermissionTreeVo toTreeNode(PermissionDef def) {
        PermissionTreeVo vo = new PermissionTreeVo();
        vo.setCode(def.code());
        vo.setName(def.name());
        vo.setParentCode(def.parentCode());
        vo.setResources(def.resources().stream()
                .map(r -> new ResourceVo(r.code(), r.name()))
                .toList());
        return vo;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            if (root == null) {
                throw new IllegalStateException("permissions.yml 内容为空");
            }
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("permissions.yml 读取失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<ResourceDef> parseResources(Object node, String location) {
        List<ResourceDef> result = new ArrayList<>();
        if (node == null) {
            return result;
        }
        if (!(node instanceof List<?> list)) {
            throw new IllegalStateException("permissions.yml 格式错误：" + location + " 必须是列表");
        }
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map)) {
                throw new IllegalStateException("permissions.yml 格式错误：" + location + "[" + i + "] 必须是对象");
            }
            result.add(toResourceDef((Map<String, Object>) list.get(i), location + "[" + i + "]"));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, PermissionDef> parsePermissions(Object node) {
        Map<String, PermissionDef> result = new LinkedHashMap<>();
        if (node == null) {
            return result;
        }
        if (!(node instanceof List<?> list)) {
            throw new IllegalStateException("permissions.yml 格式错误：permissions 必须是列表");
        }
        for (int i = 0; i < list.size(); i++) {
            String location = "permissions[" + i + "]";
            if (!(list.get(i) instanceof Map)) {
                throw new IllegalStateException("permissions.yml 格式错误：" + location + " 必须是对象");
            }
            Map<String, Object> map = (Map<String, Object>) list.get(i);
            String code = requireText(map.get("code"), location + ".code");
            String name = requireText(map.get("name"), location + ".name");
            Object parent = map.get("parent");
            String parentCode = parent == null ? null : requireText(parent, location + ".parent");
            List<ResourceDef> resources = parseResources(map.get("resources"), location + ".resources");
            if (result.put(code, new PermissionDef(code, name, parentCode, resources)) != null) {
                throw new IllegalStateException("permissions.yml 校验失败：权限编码重复 " + code);
            }
        }
        return result;
    }

    private ResourceDef toResourceDef(Map<String, Object> map, String location) {
        return new ResourceDef(
                requireText(map.get("code"), location + ".code"),
                requireText(map.get("name"), location + ".name"));
    }

    private String requireText(Object value, String location) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("permissions.yml 格式错误：" + location + " 缺失或不是非空字符串");
        }
        return text;
    }

    private void validate() {
        // 资源编码全局唯一且格式合法
        Set<String> resourceCodes = new HashSet<>();
        List<ResourceDef> all = new ArrayList<>();
        all.addAll(publicResources);
        all.addAll(loginResources);
        permissions.values().forEach(p -> all.addAll(p.resources()));
        for (ResourceDef def : all) {
            if (!resourceCodes.add(def.code())) {
                throw new IllegalStateException("permissions.yml 校验失败：资源编码重复 " + def.code());
            }
            if (!def.code().startsWith(VIEW_PREFIX) && !API_CODE_PATTERN.matcher(def.code()).matches()) {
                throw new IllegalStateException("permissions.yml 校验失败：资源编码格式非法 " + def.code()
                        + "（应为 METHOD:path 或 VIEW: 前缀）");
            }
        }
        // 父权限引用必须存在
        for (PermissionDef def : permissions.values()) {
            if (def.parentCode() != null && !permissions.containsKey(def.parentCode())) {
                throw new IllegalStateException("permissions.yml 校验失败：权限 " + def.code()
                        + " 的父权限不存在 " + def.parentCode());
            }
        }
        // 权限树不能有环
        for (String code : permissions.keySet()) {
            detectCycle(code);
        }
    }

    private void detectCycle(String code) {
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        String current = code;
        while (current != null) {
            if (!visited.add(current)) {
                throw new IllegalStateException("permissions.yml 校验失败：权限树存在环 " + stack + " -> " + current);
            }
            stack.push(current);
            PermissionDef def = permissions.get(current);
            current = def == null ? null : def.parentCode();
        }
    }

    /**
     * 获取所有被引用但不存在的权限编码（用于启动时审计角色绑定）。
     *
     * @param codes 待检查的权限编码
     * @return 不存在的编码列表
     */
    public List<String> findUnknownCodes(Collection<String> codes) {
        if (codes == null) {
            return List.of();
        }
        return codes.stream().filter(c -> !permissions.containsKey(c)).distinct().toList();
    }
}
