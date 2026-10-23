package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.AuthConstant;
import com.fleyx.jcloud.config.AuthProperties;
import com.fleyx.jcloud.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AuthCookieSupport 单元测试（无 Spring 上下文），覆盖 Secure 自动判定的三分支：
 * X-Forwarded-Proto=https、request.isSecure()、纯 HTTP。
 */
class AuthCookieSupportTest {

    private static AuthCookieSupport newSupport() {
        return new AuthCookieSupport(new AuthProperties(), new JwtProperties());
    }

    private static MockHttpServletRequest httpsByForwardedProto() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Proto", "https");
        return request;
    }

    private static MockHttpServletRequest httpsBySecureRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        return request;
    }

    private static MockHttpServletRequest plainHttpRequest() {
        return new MockHttpServletRequest();
    }

    private static List<String> setCookieHeaders(MockHttpServletResponse response) {
        return response.getHeaders(HttpHeaders.SET_COOKIE);
    }

    private static String findCookie(List<String> cookies, String prefix) {
        return cookies.stream().filter(c -> c.startsWith(prefix)).findFirst().orElseThrow();
    }

    /**
     * writeTokenCookies：X-Forwarded-Proto=https 时 access/refresh 两条 Set-Cookie 均带 Secure。
     */
    @Test
    void shouldWriteSecureCookiesWhenForwardedProtoIsHttps() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        newSupport().writeTokenCookies(httpsByForwardedProto(), response, "jwt-token", "refresh-token");

        List<String> cookies = setCookieHeaders(response);
        assertEquals(2, cookies.size());
        assertTrue(findCookie(cookies, AuthConstant.ACCESS_TOKEN_COOKIE + "=jwt-token").contains("Secure"));
        assertTrue(findCookie(cookies, AuthConstant.REFRESH_TOKEN_COOKIE + "=refresh-token").contains("Secure"));
    }

    /**
     * writeTokenCookies：request.isSecure()=true（无转发头）时两条 Set-Cookie 均带 Secure。
     */
    @Test
    void shouldWriteSecureCookiesWhenRequestIsSecure() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        newSupport().writeTokenCookies(httpsBySecureRequest(), response, "jwt-token", "refresh-token");

        List<String> cookies = setCookieHeaders(response);
        assertEquals(2, cookies.size());
        assertTrue(findCookie(cookies, AuthConstant.ACCESS_TOKEN_COOKIE + "=jwt-token").contains("Secure"));
        assertTrue(findCookie(cookies, AuthConstant.REFRESH_TOKEN_COOKIE + "=refresh-token").contains("Secure"));
    }

    /**
     * writeTokenCookies：纯 HTTP（无转发头且 isSecure=false）时 Set-Cookie 不带 Secure。
     */
    @Test
    void shouldWriteNonSecureCookiesWhenRequestIsPlainHttp() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        newSupport().writeTokenCookies(plainHttpRequest(), response, "jwt-token", "refresh-token");

        List<String> cookies = setCookieHeaders(response);
        assertEquals(2, cookies.size());
        assertFalse(findCookie(cookies, AuthConstant.ACCESS_TOKEN_COOKIE + "=jwt-token").contains("Secure"),
                "纯 HTTP 请求不应带 Secure");
        assertFalse(findCookie(cookies, AuthConstant.REFRESH_TOKEN_COOKIE + "=refresh-token").contains("Secure"),
                "纯 HTTP 请求不应带 Secure");
    }

    /**
     * clearTokenCookies：X-Forwarded-Proto=https 时两条清除 cookie 均带 Secure（与写入同属性才能覆盖清除）。
     */
    @Test
    void shouldClearSecureCookiesWhenForwardedProtoIsHttps() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        newSupport().clearTokenCookies(httpsByForwardedProto(), response);

        List<String> cookies = setCookieHeaders(response);
        assertEquals(2, cookies.size());
        assertTrue(findCookie(cookies, AuthConstant.ACCESS_TOKEN_COOKIE + "=").contains("Secure"));
        assertTrue(findCookie(cookies, AuthConstant.REFRESH_TOKEN_COOKIE + "=").contains("Secure"));
    }

    /**
     * clearTokenCookies：request.isSecure()=true（无转发头）时两条清除 cookie 均带 Secure。
     */
    @Test
    void shouldClearSecureCookiesWhenRequestIsSecure() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        newSupport().clearTokenCookies(httpsBySecureRequest(), response);

        List<String> cookies = setCookieHeaders(response);
        assertEquals(2, cookies.size());
        assertTrue(findCookie(cookies, AuthConstant.ACCESS_TOKEN_COOKIE + "=").contains("Secure"));
        assertTrue(findCookie(cookies, AuthConstant.REFRESH_TOKEN_COOKIE + "=").contains("Secure"));
    }

    /**
     * clearTokenCookies：纯 HTTP（无转发头且 isSecure=false）时清除 cookie 不带 Secure。
     */
    @Test
    void shouldClearNonSecureCookiesWhenRequestIsPlainHttp() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        newSupport().clearTokenCookies(plainHttpRequest(), response);

        List<String> cookies = setCookieHeaders(response);
        assertEquals(2, cookies.size());
        assertFalse(findCookie(cookies, AuthConstant.ACCESS_TOKEN_COOKIE + "=").contains("Secure"));
        assertFalse(findCookie(cookies, AuthConstant.REFRESH_TOKEN_COOKIE + "=").contains("Secure"));
    }
}
