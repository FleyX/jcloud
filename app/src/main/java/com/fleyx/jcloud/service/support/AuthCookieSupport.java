package com.fleyx.jcloud.service.support;

import cn.hutool.core.util.StrUtil;
import com.fleyx.jcloud.common.constant.AuthConstant;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.config.AuthProperties;
import com.fleyx.jcloud.config.JwtProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 认证 cookie 构建/写入/清除的唯一出口。
 * <p>
 * 访问令牌与刷新令牌均以 HttpOnly、SameSite=Strict 写入；访问令牌 Path=/（全站可见），
 * 刷新令牌 Path 收窄至鉴权接口前缀（{@link CommonConstant#API}/auth），业务请求不再携带刷新令牌；
 * 刷新令牌为持久 cookie，Max-Age 跟随 {@link AuthProperties#getRefreshExpireDays()} 的滑动有效期，
 * 访问令牌 Max-Age 跟随 JWT 有效期（{@link JwtProperties#getExpireHours()}）；Secure 由配置项
 * {@code cookieSecure} 控制、默认关闭以兼容局域网纯 HTTP。
 */
@Component
@RequiredArgsConstructor
public class AuthCookieSupport {

    /** SameSite 取值。 */
    private static final String SAME_SITE_STRICT = "Strict";

    /** 访问令牌 cookie 作用路径（全站可见）。 */
    private static final String ACCESS_COOKIE_PATH = "/";

    /** 刷新令牌 cookie 作用路径（鉴权接口前缀，业务路径不携带刷新令牌）。 */
    private static final String REFRESH_COOKIE_PATH = CommonConstant.API + "/auth";

    private final AuthProperties authProperties;
    private final JwtProperties jwtProperties;

    /**
     * 写入访问令牌与刷新令牌 cookie。
     *
     * @param response     当前响应
     * @param accessToken  访问令牌
     * @param refreshToken 刷新令牌
     */
    public void writeTokenCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        ResponseCookie accessCookie = ResponseCookie.from(AuthConstant.ACCESS_TOKEN_COOKIE, accessToken)
                .httpOnly(true)
                .path(ACCESS_COOKIE_PATH)
                .sameSite(SAME_SITE_STRICT)
                .secure(authProperties.isCookieSecure())
                .maxAge(Duration.ofHours(jwtProperties.getExpireHours()))
                .build();
        ResponseCookie refreshCookie = ResponseCookie.from(AuthConstant.REFRESH_TOKEN_COOKIE, refreshToken)
                .httpOnly(true)
                .path(REFRESH_COOKIE_PATH)
                .sameSite(SAME_SITE_STRICT)
                .secure(authProperties.isCookieSecure())
                .maxAge(Duration.ofDays(authProperties.getRefreshExpireDays()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
    }

    /**
     * 清除访问令牌与刷新令牌 cookie（Max-Age=0）。
     *
     * @param response 当前响应
     */
    public void clearTokenCookies(HttpServletResponse response) {
        ResponseCookie accessCookie = ResponseCookie.from(AuthConstant.ACCESS_TOKEN_COOKIE, "")
                .httpOnly(true)
                .path(ACCESS_COOKIE_PATH)
                .sameSite(SAME_SITE_STRICT)
                .secure(authProperties.isCookieSecure())
                .maxAge(Duration.ZERO)
                .build();
        ResponseCookie refreshCookie = ResponseCookie.from(AuthConstant.REFRESH_TOKEN_COOKIE, "")
                .httpOnly(true)
                .path(REFRESH_COOKIE_PATH)
                .sameSite(SAME_SITE_STRICT)
                .secure(authProperties.isCookieSecure())
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
    }

    /**
     * 解析登出所需的刷新令牌：body 非空优先用 body，否则读刷新令牌 cookie
     * （Web 端 JS 读不到 HttpOnly cookie，只能从请求中的 cookie 携带）。
     *
     * @param request   当前请求
     * @param bodyToken body 中的刷新令牌（可为空）
     * @return 刷新令牌，均不存在时返回 null
     */
    public String resolveRefreshToken(HttpServletRequest request, String bodyToken) {
        if (StrUtil.isNotBlank(bodyToken)) {
            return bodyToken;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AuthConstant.REFRESH_TOKEN_COOKIE.equals(cookie.getName()) && StrUtil.isNotBlank(cookie.getValue())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
