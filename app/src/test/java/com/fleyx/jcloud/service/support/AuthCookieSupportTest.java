package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.AuthConstant;
import com.fleyx.jcloud.config.AuthProperties;
import com.fleyx.jcloud.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AuthCookieSupport 单元测试（无 Spring 上下文），覆盖 cookieSecure=true/false 两分支。
 */
class AuthCookieSupportTest {

    private AuthCookieSupport newSupport(boolean cookieSecure) {
        AuthProperties authProperties = new AuthProperties();
        authProperties.setCookieSecure(cookieSecure);
        return new AuthCookieSupport(authProperties, new JwtProperties());
    }

    private static List<String> setCookieHeaders(MockHttpServletResponse response) {
        return response.getHeaders(HttpHeaders.SET_COOKIE);
    }

    private static String findCookie(List<String> cookies, String prefix) {
        return cookies.stream().filter(c -> c.startsWith(prefix)).findFirst().orElseThrow();
    }

    /**
     * writeTokenCookies：cookieSecure=true 时 access/refresh 两条 Set-Cookie 均带 Secure。
     */
    @Test
    void shouldWriteSecureCookiesWhenCookieSecureEnabled() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        newSupport(true).writeTokenCookies(response, "jwt-token", "refresh-token");

        List<String> cookies = setCookieHeaders(response);
        assertEquals(2, cookies.size());
        assertTrue(findCookie(cookies, AuthConstant.ACCESS_TOKEN_COOKIE + "=jwt-token").contains("Secure"));
        assertTrue(findCookie(cookies, AuthConstant.REFRESH_TOKEN_COOKIE + "=refresh-token").contains("Secure"));
    }

    /**
     * writeTokenCookies：cookieSecure=false（默认）时 Set-Cookie 不带 Secure，行为与现状一致。
     */
    @Test
    void shouldWriteNonSecureCookiesWhenCookieSecureDisabled() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        newSupport(false).writeTokenCookies(response, "jwt-token", "refresh-token");

        List<String> cookies = setCookieHeaders(response);
        assertEquals(2, cookies.size());
        assertFalse(findCookie(cookies, AuthConstant.ACCESS_TOKEN_COOKIE + "=jwt-token").contains("Secure"),
                "Secure 默认关闭时不应出现");
        assertFalse(findCookie(cookies, AuthConstant.REFRESH_TOKEN_COOKIE + "=refresh-token").contains("Secure"),
                "Secure 默认关闭时不应出现");
    }

    /**
     * clearTokenCookies：cookieSecure=true 时两条清除 cookie 均带 Secure（与写入同属性才能覆盖清除）。
     */
    @Test
    void shouldClearSecureCookiesWhenCookieSecureEnabled() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        newSupport(true).clearTokenCookies(response);

        List<String> cookies = setCookieHeaders(response);
        assertEquals(2, cookies.size());
        assertTrue(findCookie(cookies, AuthConstant.ACCESS_TOKEN_COOKIE + "=").contains("Secure"));
        assertTrue(findCookie(cookies, AuthConstant.REFRESH_TOKEN_COOKIE + "=").contains("Secure"));
    }

    /**
     * clearTokenCookies：cookieSecure=false 时清除 cookie 不带 Secure。
     */
    @Test
    void shouldClearNonSecureCookiesWhenCookieSecureDisabled() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        newSupport(false).clearTokenCookies(response);

        List<String> cookies = setCookieHeaders(response);
        assertEquals(2, cookies.size());
        assertFalse(findCookie(cookies, AuthConstant.ACCESS_TOKEN_COOKIE + "=").contains("Secure"));
        assertFalse(findCookie(cookies, AuthConstant.REFRESH_TOKEN_COOKIE + "=").contains("Secure"));
    }
}
