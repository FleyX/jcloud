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
 * 访问令牌 Max-Age 跟随 JWT 有效期（{@link JwtProperties#getExpireHours()}）；Secure 标记按当前请求
 * 动态判定：{@code X-Forwarded-Proto: https}（反向代理）或 {@code request.isSecure()} 为 true 时携带，
 * 纯 HTTP 请求不携带（探测失败方向为不安全，功能可用、安全降级）。
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

    /** 反向代理透传的原始请求协议头。 */
    private static final String FORWARDED_PROTO_HEADER = "X-Forwarded-Proto";

    private final AuthProperties authProperties;
    private final JwtProperties jwtProperties;

    /**
     * 写入访问令牌与刷新令牌 cookie。
     *
     * @param request      当前请求（用于判定 Secure）
     * @param response     当前响应
     * @param accessToken  访问令牌
     * @param refreshToken 刷新令牌
     */
    public void writeTokenCookies(HttpServletRequest request, HttpServletResponse response,
                                  String accessToken, String refreshToken) {
        boolean secure = isSecureRequest(request);
        ResponseCookie accessCookie = ResponseCookie.from(AuthConstant.ACCESS_TOKEN_COOKIE, accessToken)
                .httpOnly(true)
                .path(ACCESS_COOKIE_PATH)
                .sameSite(SAME_SITE_STRICT)
                .secure(secure)
                .maxAge(Duration.ofHours(jwtProperties.getExpireHours()))
                .build();
        ResponseCookie refreshCookie = ResponseCookie.from(AuthConstant.REFRESH_TOKEN_COOKIE, refreshToken)
                .httpOnly(true)
                .path(REFRESH_COOKIE_PATH)
                .sameSite(SAME_SITE_STRICT)
                .secure(secure)
                .maxAge(Duration.ofDays(authProperties.getRefreshExpireDays()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
    }

    /**
     * 清除访问令牌与刷新令牌 cookie（Max-Age=0）。
     *
     * @param request  当前请求（用于判定 Secure）
     * @param response 当前响应
     */
    public void clearTokenCookies(HttpServletRequest request, HttpServletResponse response) {
        boolean secure = isSecureRequest(request);
        ResponseCookie accessCookie = ResponseCookie.from(AuthConstant.ACCESS_TOKEN_COOKIE, "")
                .httpOnly(true)
                .path(ACCESS_COOKIE_PATH)
                .sameSite(SAME_SITE_STRICT)
                .secure(secure)
                .maxAge(Duration.ZERO)
                .build();
        ResponseCookie refreshCookie = ResponseCookie.from(AuthConstant.REFRESH_TOKEN_COOKIE, "")
                .httpOnly(true)
                .path(REFRESH_COOKIE_PATH)
                .sameSite(SAME_SITE_STRICT)
                .secure(secure)
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
    }

    /**
     * 判定当前请求是否走安全连接：{@code X-Forwarded-Proto} 首个（逗号分隔）token 为 https，
     * 或请求本身为安全连接。头缺失/为 http/其他值时不带 Secure（安全降级，不影响可用性）。
     *
     * @param request 当前请求
     * @return 是否携带 Secure
     */
    private static boolean isSecureRequest(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String forwardedProto = request.getHeader(FORWARDED_PROTO_HEADER);
        if (StrUtil.isBlank(forwardedProto)) {
            return false;
        }
        String proto = StrUtil.subBefore(forwardedProto, ",", false);
        return "https".equalsIgnoreCase(StrUtil.trim(proto));
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
