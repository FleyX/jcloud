package com.fleyx.jcloud.filter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.cache.UserPermissionCache;
import com.fleyx.jcloud.common.permission.PermissionRegistry;
import com.fleyx.jcloud.common.permission.PermissionResolver;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AuthTokenFilter} 单元测试。
 * <p>
 * 验证资源在构造时从权限注册表一次性加载，token 解析失败（过期/伪造）的异常分支，
 * 以及权限校验逻辑（超级管理员放行、query 参数 token 回退）正确。
 */
class AuthTokenFilterTest {

    private static final String PUBLIC_PATH = "/jcloud/api/public/ping";
    private static final String LOGIN_PATH = "/jcloud/api/auth/me";
    private static final String API_PATH = "/jcloud/api/files";

    private JwtUtil jwtUtil;
    private ObjectMapper objectMapper;
    private PermissionRegistry permissionRegistry;
    private UserMapper userMapper;
    private UserRoleMapper userRoleMapper;
    private UserPermissionCache userPermissionCache;
    private PermissionResolver permissionResolver;

    private AuthTokenFilter filter;

    @BeforeEach
    void setUp() {
        jwtUtil = mock(JwtUtil.class);
        objectMapper = new ObjectMapper();
        permissionRegistry = mock(PermissionRegistry.class);
        userMapper = mock(UserMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        userPermissionCache = new UserPermissionCache();
        permissionResolver = mock(PermissionResolver.class);

        List<PermissionRegistry.FilterResourceEntry> entries = List.of(
                new PermissionRegistry.FilterResourceEntry("GET:" + PUBLIC_PATH, PermissionRegistry.TYPE_PUBLIC),
                new PermissionRegistry.FilterResourceEntry("GET:" + LOGIN_PATH, PermissionRegistry.TYPE_LOGIN)
        );
        when(permissionRegistry.filterEntries()).thenReturn(entries);

        filter = new AuthTokenFilter(jwtUtil, objectMapper, permissionRegistry,
                userMapper, userRoleMapper, userPermissionCache, permissionResolver);
    }

    @Test
    void publicResourceShouldPassWithoutToken() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PUBLIC_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() != null);
    }

    @Test
    void protectedResourceWithoutTokenShouldReturn401() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
    }

    @Test
    void loginResourceWithValidTokenShouldPass() throws ServletException, IOException {
        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("valid-token")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn("1");
        when(jwtUtil.getUserCode(claims)).thenReturn("user");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", LOGIN_PATH);
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
    }

    @Test
    void authorizedResourceWithValidTokenShouldPass() throws ServletException, IOException {
        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("valid-token")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn("1");
        when(jwtUtil.getUserCode(claims)).thenReturn("user");

        User user = new User();
        user.setId("1");
        user.setIsAdmin(0);
        when(userMapper.selectById("1")).thenReturn(user);
        when(permissionResolver.resolveResourceCodes(any())).thenReturn(List.of("GET:" + API_PATH));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
    }

    @Test
    void unauthorizedResourceWithValidTokenShouldReturn403() throws ServletException, IOException {
        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("valid-token")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn("1");
        when(jwtUtil.getUserCode(claims)).thenReturn("user");

        User user = new User();
        user.setId("1");
        user.setIsAdmin(0);
        when(userMapper.selectById("1")).thenReturn(user);
        when(permissionResolver.resolveResourceCodes(any())).thenReturn(List.of());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
    }

    @Test
    void expiredTokenShouldReturn401WithExpiredMessage() throws ServletException, IOException {
        when(jwtUtil.parseToken("expired-token")).thenThrow(
                new ExpiredJwtException(Jwts.header().build(), Jwts.claims().build(), "expired"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
        request.addHeader("Authorization", "Bearer expired-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals(401, body.get("code").asInt());
        assertEquals("登录凭证已过期", body.get("msg").asText());
    }

    @Test
    void invalidTokenShouldReturn401WithInvalidMessage() throws ServletException, IOException {
        when(jwtUtil.parseToken("forged-token")).thenThrow(new JwtException("invalid token"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
        request.addHeader("Authorization", "Bearer forged-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals(401, body.get("code").asInt());
        assertEquals("登录凭证无效", body.get("msg").asText());
    }

    @Test
    void superAdminShouldSkipAuthorizationCheck() throws ServletException, IOException {
        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("valid-token")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn("1");
        when(jwtUtil.getUserCode(claims)).thenReturn("user");

        User user = new User();
        user.setId("1");
        user.setIsAdmin(1);
        when(userMapper.selectById("1")).thenReturn(user);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() != null);
    }

    @Test
    void tokenViaQueryParamShouldPass() throws ServletException, IOException {
        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("valid-token")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn("1");
        when(jwtUtil.getUserCode(claims)).thenReturn("user");

        User user = new User();
        user.setId("1");
        user.setIsAdmin(0);
        when(userMapper.selectById("1")).thenReturn(user);
        when(permissionResolver.resolveResourceCodes(any())).thenReturn(List.of("GET:" + API_PATH));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
        request.addParameter("token", "valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() != null);
    }

    @Test
    void loginResourceWithoutTokenShouldReturn401() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", LOGIN_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals(401, body.get("code").asInt());
        assertEquals("缺少登录凭证", body.get("msg").asText());
    }

    /**
     * 仅携带有效访问令牌 cookie 的请求应放行（Web 端双通道凭证）。
     */
    @Test
    void tokenViaAccessCookieShouldPass() throws ServletException, IOException {
        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("valid-token")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn("1");
        when(jwtUtil.getUserCode(claims)).thenReturn("user");

        User user = new User();
        user.setId("1");
        user.setIsAdmin(0);
        when(userMapper.selectById("1")).thenReturn(user);
        when(permissionResolver.resolveResourceCodes(any())).thenReturn(List.of("GET:" + API_PATH));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
        request.setCookies(new Cookie("jcloud_access_token", "valid-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() != null);
    }

    /**
     * 仅携带无关 cookie（无访问令牌）的请求应 401（cookie 通道按名字精确匹配）。
     */
    @Test
    void unrelatedCookieShouldNotAuthenticate() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
        request.setCookies(new Cookie("some_other_cookie", "whatever"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
    }

    /**
     * header 通道与 cookie 通道互不干扰：携带 header 时仍按 header 放行。
     */
    @Test
    void headerChannelStillPassesWhenCookieAbsent() throws ServletException, IOException {
        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("valid-token")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn("1");
        when(jwtUtil.getUserCode(claims)).thenReturn("user");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", LOGIN_PATH);
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() != null);
    }

}
