package com.fleyx.jcloud.filter;

import cn.hutool.core.util.StrUtil;
import tools.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.R;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.cache.UserPermissionCache;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.CommonStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.permission.PermissionResolver;
import com.fleyx.jcloud.mapper.ResourceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.po.Resource;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Token 认证与鉴权过滤器。
 * 启动时加载有效资源，逻辑：
 * 1. PUBLIC 类型资源直接放行；
 * 2. 非 PUBLIC 资源必须携带有效 JWT；
 * 3. LOGIN 类型资源仅需 JWT 有效即可放行；
 * 4. 其它资源根据用户角色查询对应的资源 URL，判断当前请求是否在授权列表中；
 * 5. 超级管理员直接放行所有非 PUBLIC 接口。
 */
@RequiredArgsConstructor
public class AuthTokenFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * PUBLIC 类型资源：无需登录与权限校验即可访问。
     */
    private static final String RESOURCE_TYPE_PUBLIC = "PUBLIC";

    /**
     * LOGIN 类型资源：仅需登录（JWT 有效）即可访问，不校验具体权限。
     */
    private static final String RESOURCE_TYPE_LOGIN = "LOGIN";

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final ResourceMapper resourceMapper;
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserPermissionCache userPermissionCache;
    private final PermissionResolver permissionResolver;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private volatile List<ResourceEntry> resourceEntries;

    /**
     * 启动时加载有效资源。
     */
    private void loadResources() {
        List<Resource> resources = resourceMapper.selectList(
                new LambdaQueryWrapper<Resource>()
                        .eq(Resource::getStatus, CommonStatus.ENABLED.getCode())
        );
        resourceEntries = resources.stream()
                .map(r -> new ResourceEntry(r.getCode(), r.getType()))
                .toList();
    }

    /**
     * 解析请求资源 key，返回资源类型匹配结果。
     *
     * @param resourceKey 请求方法 + URI，例如 GET:/jcloud/api/users
     * @return 资源鉴权结果
     */
    private ResourcePermissionResult resolve(String resourceKey) {
        boolean publicResource = false;
        boolean loginResource = false;
        for (ResourceEntry entry : getResourceEntries()) {
            if (pathMatcher.match(entry.pattern(), resourceKey)) {
                if (RESOURCE_TYPE_PUBLIC.equals(entry.type())) {
                    publicResource = true;
                } else if (RESOURCE_TYPE_LOGIN.equals(entry.type())) {
                    loginResource = true;
                }
            }
        }
        return new ResourcePermissionResult(publicResource, loginResource);
    }

    private List<ResourceEntry> getResourceEntries() {
        if (resourceEntries == null) {
            synchronized (this) {
                if (resourceEntries == null) {
                    loadResources();
                }
            }
        }
        return resourceEntries;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String resourceKey = buildResourceKey(request);
        ResourcePermissionResult result = resolve(resourceKey);

        // 1. PUBLIC 资源直接放行
        if (result.publicResource()) {
            try {
                filterChain.doFilter(request, response);
            } finally {
                UserContext.clear();
            }
            return;
        }

        // 2. 非 PUBLIC 资源必须携带 JWT
        String token = extractToken(request);
        if (StrUtil.isBlank(token)) {
            writeResponse(response, ResultCode.UNAUTHORIZED, "缺少登录凭证");
            return;
        }

        Long userId;
        CurrentUser currentUser;
        try {
            Claims claims = jwtUtil.parseToken(token);
            userId = jwtUtil.getUserId(claims);
            currentUser = new CurrentUser(userId, jwtUtil.getUserCode(claims));
            request.setAttribute(CurrentUser.class.getName(), currentUser);
        } catch (ExpiredJwtException e) {
            writeResponse(response, ResultCode.UNAUTHORIZED, "登录凭证已过期");
            return;
        } catch (JwtException | IllegalArgumentException e) {
            writeResponse(response, ResultCode.UNAUTHORIZED, "登录凭证无效");
            return;
        }

        try {
            UserContext.set(currentUser);
            // 3. LOGIN 资源直接放行
            if (result.loginResource()) {
                filterChain.doFilter(request, response);
                return;
            }

            // 4. URL 鉴权
            if (!isAuthorized(userId, resourceKey)) {
                writeResponse(response, ResultCode.FORBIDDEN, "权限不足");
                return;
            }
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    private boolean isAuthorized(Long userId, String resourceKey) {
        UserPermissionCache.CacheValue cached = userPermissionCache.get(userId);
        if (cached != null) {
            return checkAuthorization(cached.superAdmin(), cached.resourceCodes(), resourceKey);
        }

        User user = userMapper.selectById(userId);
        boolean superAdmin = user != null && user.isSuperAdmin();
        List<Long> roleIds = userRoleMapper.selectRoleIdsByUserId(userId);
        List<String> resourceCodes = permissionResolver.resolveResourceCodes(roleIds);
        userPermissionCache.put(userId, roleIds, resourceCodes, superAdmin);
        return checkAuthorization(superAdmin, resourceCodes, resourceKey);
    }

    private boolean checkAuthorization(boolean superAdmin, List<String> resourceCodes, String resourceKey) {
        if (superAdmin) {
            return true;
        }
        if (resourceCodes.isEmpty()) {
            return false;
        }
        return resourceCodes.stream().anyMatch(pattern -> pathMatcher.match(pattern, resourceKey));
    }

    private String buildResourceKey(HttpServletRequest request) {
        return request.getMethod() + ":" + request.getRequestURI();
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StrUtil.isNotBlank(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private void writeResponse(HttpServletResponse response, ResultCode code, String message) throws IOException {
        response.setStatus(code == ResultCode.UNAUTHORIZED ? HttpServletResponse.SC_UNAUTHORIZED : HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        R<Void> body = R.fail(code, message);
        objectMapper.writeValue(response.getWriter(), body);
    }

    private record ResourceEntry(String pattern, String type) {
    }

    /**
     * 资源鉴权解析结果。
     *
     * @param publicResource 是否为 PUBLIC 资源
     * @param loginResource  是否为 LOGIN 资源
     */
    private record ResourcePermissionResult(boolean publicResource, boolean loginResource) {
    }
}
