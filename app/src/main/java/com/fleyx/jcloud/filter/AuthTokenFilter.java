package com.fleyx.jcloud.filter;

import cn.hutool.core.util.StrUtil;
import tools.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.cache.UserPermissionCache;
import com.fleyx.jcloud.common.constant.AuthConstant;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.permission.PermissionRegistry;
import com.fleyx.jcloud.common.permission.PermissionResolver;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.bo.RefreshResult;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.service.support.AuthBlacklistSupport;
import com.fleyx.jcloud.service.support.AuthCookieSupport;
import com.fleyx.jcloud.service.support.AuthSessionSupport;
import com.fleyx.jcloud.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Token 认证与鉴权过滤器。
 * 启动时从内存权限注册表加载 PUBLIC/LOGIN 资源，逻辑：
 * 1. PUBLIC 类型资源直接放行；
 * 2. 非 PUBLIC 资源必须携带有效 JWT；
 * 3. LOGIN 类型资源仅需 JWT 有效即可放行；
 * 4. 其它资源根据用户角色查询对应的资源 URL，判断当前请求是否在授权列表中；
 * 5. 超级管理员直接放行所有非 PUBLIC 接口。
 */
@Slf4j
public class AuthTokenFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /** 凭证来源：Authorization header（原生端）。 */
    private static final String CHANNEL_HEADER = "header";
    /** 凭证来源：访问令牌 cookie（Web 端）。 */
    private static final String CHANNEL_COOKIE = "cookie";
    /** 凭证来源：token 查询参数（媒体流等场景）。 */
    private static final String CHANNEL_PARAM = "param";

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final PermissionRegistry permissionRegistry;
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserPermissionCache userPermissionCache;
    private final PermissionResolver permissionResolver;
    private final AuthSessionSupport authSessionSupport;
    private final AuthCookieSupport authCookieSupport;
    private final AuthBlacklistSupport authBlacklistSupport;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private final List<ResourceEntry> resourceEntries;

    public AuthTokenFilter(JwtUtil jwtUtil, ObjectMapper objectMapper, PermissionRegistry permissionRegistry,
                           UserMapper userMapper, UserRoleMapper userRoleMapper,
                           UserPermissionCache userPermissionCache, PermissionResolver permissionResolver,
                           AuthSessionSupport authSessionSupport, AuthCookieSupport authCookieSupport,
                           AuthBlacklistSupport authBlacklistSupport) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
        this.permissionRegistry = permissionRegistry;
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.userPermissionCache = userPermissionCache;
        this.permissionResolver = permissionResolver;
        this.authSessionSupport = authSessionSupport;
        this.authCookieSupport = authCookieSupport;
        this.authBlacklistSupport = authBlacklistSupport;
        this.resourceEntries = loadResources();
    }

    /**
     * 启动时从权限注册表加载 PUBLIC/LOGIN 资源。
     */
    private List<ResourceEntry> loadResources() {
        return permissionRegistry.filterEntries().stream()
                .map(e -> new ResourceEntry(e.pattern(), e.type()))
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
        for (ResourceEntry entry : resourceEntries) {
            if (pathMatcher.match(entry.pattern(), resourceKey)) {
                if (PermissionRegistry.TYPE_PUBLIC.equals(entry.type())) {
                    publicResource = true;
                } else if (PermissionRegistry.TYPE_LOGIN.equals(entry.type())) {
                    loginResource = true;
                }
            }
        }
        return new ResourcePermissionResult(publicResource, loginResource);
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
        TokenSource tokenSource = extractToken(request);
        if (StrUtil.isBlank(tokenSource.token())) {
            writeResponse(response, ResultCode.UNAUTHORIZED, "缺少登录凭证");
            return;
        }

        Claims claims;
        try {
            claims = jwtUtil.parseToken(tokenSource.token());
        } catch (ExpiredJwtException e) {
            // 3. 访问令牌过期：仅 cookie 通道尝试静默续期，失败返回 401
            Claims renewed = silentRenew(request, response, tokenSource);
            if (renewed == null) {
                return;
            }
            claims = renewed;
        } catch (JwtException | IllegalArgumentException e) {
            writeResponse(response, ResultCode.UNAUTHORIZED, "登录凭证无效");
            return;
        }

        proceed(request, response, filterChain, result, resourceKey, claims);
    }

    /**
     * 令牌有效后统一走 UserContext 与鉴权流程（正常放行与静默续期后的入口）。
     */
    private void proceed(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain,
                         ResourcePermissionResult result, String resourceKey, Claims claims)
            throws ServletException, IOException {
        String userId = jwtUtil.getUserId(claims);
        // 黑名单校验：携带设备标识的令牌若签发时间不晚于吊销时间戳则判定已吊销（cookie 与 header 通道一视同仁）
        String deviceId = jwtUtil.getDeviceId(claims);
        if (StrUtil.isNotBlank(deviceId) && claims.getIssuedAt() != null
                && authBlacklistSupport.isRevoked(userId, deviceId, claims.getIssuedAt().getTime())) {
            log.info("黑名单命中，拒绝访问 userId={}, deviceId={}", userId, deviceId);
            writeResponse(response, ResultCode.UNAUTHORIZED, "登录状态已失效");
            return;
        }
        CurrentUser currentUser = new CurrentUser(userId, jwtUtil.getUserCode(claims));
        request.setAttribute(CurrentUser.class.getName(), currentUser);
        try {
            UserContext.set(currentUser);
            // LOGIN 资源直接放行
            if (result.loginResource()) {
                filterChain.doFilter(request, response);
                return;
            }

            // URL 鉴权
            if (!isAuthorized(userId, resourceKey)) {
                writeResponse(response, ResultCode.FORBIDDEN, "权限不足");
                return;
            }
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    /**
     * 访问令牌过期时的静默续期。
     * <p>
     * 仅 Web 端 cookie 通道接入：读取有效刷新令牌 cookie，调用 {@link AuthSessionSupport#refresh}
     * 复用既有 Redis 轮换逻辑（含分布式锁与宽限期单飞），成功后通过 Set-Cookie 下发新令牌对并
     * 返回新 Claims 供继续鉴权；刷新令牌缺失/无效或非 cookie 通道返回 {@code null}，调用方按 401 处理。
     */
    private Claims silentRenew(HttpServletRequest request, HttpServletResponse response, TokenSource source)
            throws IOException {
        // 非 cookie 通道（原生端 header / 媒体流 param）不参与静默续期，维持 401，由原生端显式调刷新接口
        if (!CHANNEL_COOKIE.equals(source.channel())) {
            writeResponse(response, ResultCode.UNAUTHORIZED, "登录凭证已过期");
            return null;
        }
        String refreshToken = authCookieSupport.resolveRefreshToken(request, null);
        if (StrUtil.isBlank(refreshToken)) {
            writeResponse(response, ResultCode.UNAUTHORIZED, "登录凭证已过期");
            return null;
        }
        try {
            RefreshResult refreshResult = authSessionSupport.refresh(refreshToken);
            authCookieSupport.writeTokenCookies(response,
                    refreshResult.getAccessToken(), refreshResult.getRefreshToken());
            Claims claims = jwtUtil.parseToken(refreshResult.getAccessToken());
            log.debug("静默续期成功");
            return claims;
        } catch (BusinessException ex) {
            log.info("静默续期失败，刷新令牌无效或已吊销: {}", ex.getMessage());
            writeResponse(response, ResultCode.UNAUTHORIZED, "登录状态已失效");
            return null;
        }
    }

    private boolean isAuthorized(String userId, String resourceKey) {
        UserPermissionCache.CacheValue cached = userPermissionCache.get(userId);
        if (cached != null) {
            return checkAuthorization(cached.superAdmin(), cached.resourceCodes(), resourceKey);
        }

        User user = userMapper.selectById(userId);
        boolean superAdmin = user != null && user.isSuperAdmin();
        List<String> roleIds = userRoleMapper.selectRoleIdsByUserId(userId);
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

    /**
     * 按 header → cookie → ?token= 顺序提取凭证，并标记其来源通道。
     * 凭证为空白时返回 token 为空的 TokenSource。
     */
    private TokenSource extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StrUtil.isNotBlank(header) && header.startsWith(BEARER_PREFIX)) {
            return new TokenSource(header.substring(BEARER_PREFIX.length()), CHANNEL_HEADER);
        }
        // Web 端凭证走 HttpOnly cookie
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (AuthConstant.ACCESS_TOKEN_COOKIE.equals(cookie.getName())
                        && StrUtil.isNotBlank(cookie.getValue())) {
                    return new TokenSource(cookie.getValue(), CHANNEL_COOKIE);
                }
            }
        }
        // 媒体流、图片等无法携带 Authorization 头的场景，支持 token 查询参数
        String param = request.getParameter("token");
        if (StrUtil.isNotBlank(param)) {
            return new TokenSource(param, CHANNEL_PARAM);
        }
        return new TokenSource(null, null);
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
     * 提取的令牌及其来源通道。
     *
     * @param token   令牌原文，缺失/空白时为 null
     * @param channel 来源通道（header / cookie / param）
     */
    private record TokenSource(String token, String channel) {
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
