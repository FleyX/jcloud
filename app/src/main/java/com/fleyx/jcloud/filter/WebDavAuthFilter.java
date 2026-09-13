package com.fleyx.jcloud.filter;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.UserStatus;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.service.support.AuthRateLimitSupport;
import com.fleyx.jcloud.util.ClientIpUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * WebDAV 请求 Basic Auth 认证过滤器。
 * <p>
 * 仅拦截 /dav/* 路径，校验用户名/密码、用户状态、WebDAV 开关，以及 URL 中的用户编码
 * 必须与认证用户一致。携带凭据但认证失败的请求计入限流（连续失败锁定 + IP 窗口计数），
 * 锁定/限流期间返回 429 且不携带 WWW-Authenticate，避免客户端无限自动重试。
 */
@Slf4j
public class WebDavAuthFilter extends OncePerRequestFilter {

    private static final String URL_PREFIX = "/dav/";
    private static final String AUTH_HEADER = "Authorization";
    private static final String BASIC_PREFIX = "Basic ";
    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";
    private static final String REALM = "Basic realm=\"JCloud WebDAV\"";

    /**
     * HTTP 429（Servlet 规范未提供该常量）。
     */
    private static final int SC_TOO_MANY_REQUESTS = 429;

    /**
     * 限流主体前缀：WebDAV 面按用户编码计数。
     */
    private static final String RATE_LIMIT_SUBJECT_PREFIX = "webdav:";

    private final UserMapper userMapper;
    private final AuthRateLimitSupport authRateLimitSupport;

    public WebDavAuthFilter(UserMapper userMapper, AuthRateLimitSupport authRateLimitSupport) {
        this.userMapper = userMapper;
        this.authRateLimitSupport = authRateLimitSupport;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String userCode = extractUserCode(request.getRequestURI());
            if (userCode == null) {
                sendUnauthorized(response);
                return;
            }

            String clientIp = ClientIpUtil.resolve(request);
            if (!assertRateLimitAllowed(userCode, clientIp, response)) {
                return;
            }

            String[] credentials = extractCredentials(request);
            if (credentials == null) {
                // 无凭据的首次挑战请求不计入失败
                sendUnauthorized(response);
                return;
            }

            User user = authenticate(credentials[0], credentials[1], userCode);
            if (user == null) {
                authRateLimitSupport.recordWebDavFailure(rateLimitSubject(userCode), clientIp);
                sendUnauthorized(response);
                return;
            }

            authRateLimitSupport.recordSuccess(rateLimitSubject(userCode));
            UserContext.set(new CurrentUser(user.getId(), user.getUsername()));
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    /**
     * 锁定/IP 限流检查：被拦时响应 429 且不携带 WWW-Authenticate。
     *
     * @return true 表示放行，false 表示已响应 429
     */
    private boolean assertRateLimitAllowed(String userCode, String clientIp, HttpServletResponse response)
            throws IOException {
        try {
            authRateLimitSupport.assertWebDavAllowed(rateLimitSubject(userCode), clientIp);
            return true;
        } catch (BusinessException e) {
            response.sendError(SC_TOO_MANY_REQUESTS, "尝试次数过多，请稍后再试");
            return false;
        }
    }

    private String rateLimitSubject(String userCode) {
        return RATE_LIMIT_SUBJECT_PREFIX + userCode;
    }

    /**
     * 从请求 URI 中提取用户编码。
     *
     * @param uri 请求 URI
     * @return 用户编码，解析失败返回 null
     */
    private String extractUserCode(String uri) {
        if (!uri.startsWith(URL_PREFIX)) {
            return null;
        }
        String remaining = uri.substring(URL_PREFIX.length());
        int slash = remaining.indexOf('/');
        String userCode = slash < 0 ? remaining : remaining.substring(0, slash);
        return StrUtil.isBlank(userCode) ? null : userCode;
    }

    /**
     * 从 Authorization 头中提取 Basic Auth 用户名和密码。
     *
     * @param request HTTP 请求
     * @return [username, password]，提取失败返回 null
     */
    private String[] extractCredentials(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BASIC_PREFIX)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(BASIC_PREFIX.length())),
                    StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                return null;
            }
            return new String[]{decoded.substring(0, colon), decoded.substring(colon + 1)};
        } catch (IllegalArgumentException e) {
            log.debug("WebDAV Basic Auth 解码失败", e);
            return null;
        }
    }

    /**
     * 校验用户名密码、用户状态、WebDAV 开关以及 URL 用户编码一致性。
     *
     * @param username 用户名
     * @param password 密码
     * @param urlUserCode URL 中的用户编码
     * @return 认证通过的用户，失败返回 null
     */
    private User authenticate(String username, String password, String urlUserCode) {
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            return null;
        }
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        if (user == null) {
            return null;
        }
        if (!urlUserCode.equals(user.getUsername())) {
            log.debug("WebDAV 认证用户与 URL 用户编码不一致: url={}, user={}", urlUserCode, user.getUsername());
            return null;
        }
        if (user.getStatus() == null || user.getStatus() != UserStatus.ENABLED.getCode()) {
            return null;
        }
        if (!Boolean.TRUE.equals(user.getWebdavEnabled())) {
            return null;
        }
        if (!BCrypt.checkpw(password, user.getPassword())) {
            return null;
        }
        return user;
    }

    private void sendUnauthorized(HttpServletResponse response) throws IOException {
        response.setHeader(WWW_AUTHENTICATE, REALM);
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
    }
}
