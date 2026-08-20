package com.fleyx.jcloud.filter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.cache.UserPermissionCache;
import com.fleyx.jcloud.common.constant.AuthConstant;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.permission.PermissionRegistry;
import com.fleyx.jcloud.common.permission.PermissionResolver;
import com.fleyx.jcloud.config.AuthProperties;
import com.fleyx.jcloud.config.JwtProperties;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.bo.RefreshResult;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.service.support.AuthBlacklistSupport;
import com.fleyx.jcloud.service.support.AuthCookieSupport;
import com.fleyx.jcloud.service.support.AuthSessionSupport;
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
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuthTokenFilter} 单元测试。
 * <p>
 * 验证资源在构造时从权限注册表一次性加载，token 解析失败（过期/伪造）的异常分支，
 * 以及权限校验逻辑（超级管理员放行、仅有 cookie/header 通道）正确。
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
    private AuthSessionSupport authSessionSupport;
    private AuthCookieSupport authCookieSupport;
    private AuthBlacklistSupport authBlacklistSupport;

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

        AuthProperties authProperties = new AuthProperties();
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setExpireHours(24);
        authSessionSupport = mock(AuthSessionSupport.class);
        authCookieSupport = new AuthCookieSupport(authProperties, jwtProperties);
        authBlacklistSupport = mock(AuthBlacklistSupport.class);

        List<PermissionRegistry.FilterResourceEntry> entries = List.of(
                new PermissionRegistry.FilterResourceEntry("GET:" + PUBLIC_PATH, PermissionRegistry.TYPE_PUBLIC),
                new PermissionRegistry.FilterResourceEntry("GET:" + LOGIN_PATH, PermissionRegistry.TYPE_LOGIN)
        );
        when(permissionRegistry.filterEntries()).thenReturn(entries);

        filter = new AuthTokenFilter(jwtUtil, objectMapper, permissionRegistry,
                userMapper, userRoleMapper, userPermissionCache, permissionResolver,
                authSessionSupport, authCookieSupport, authBlacklistSupport);
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

    /**
     * URL 携带 ?token= 的请求不再生效，一律按无凭证 401。
     */
    @Test
    void tokenViaQueryParamShouldReturn401() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
        request.addParameter("token", "valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals(401, body.get("code").asInt());
        assertEquals("缺少登录凭证", body.get("msg").asText());
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

    /**
     * 过期 access cookie + 有效 refresh cookie → 放行，响应下发新令牌对的 Set-Cookie，UserContext 正确。
     */
    @Test
    void expiredAccessCookieWithValidRefreshCookieShouldRenewAndPass() throws ServletException, IOException {
        when(jwtUtil.parseToken("expired-access")).thenThrow(
                new ExpiredJwtException(Jwts.header().build(), Jwts.claims().build(), "expired"));
        when(authSessionSupport.refresh("valid-refresh"))
                .thenReturn(new RefreshResult("new-access", "new-refresh"));
        Claims newClaims = mock(Claims.class);
        when(jwtUtil.parseToken("new-access")).thenReturn(newClaims);
        when(jwtUtil.getUserId(newClaims)).thenReturn("1");
        when(jwtUtil.getUserCode(newClaims)).thenReturn("user");

        User user = new User();
        user.setId("1");
        user.setIsAdmin(1);
        when(userMapper.selectById("1")).thenReturn(user);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
        request.setCookies(new Cookie(AuthConstant.ACCESS_TOKEN_COOKIE, "expired-access"),
                new Cookie(AuthConstant.REFRESH_TOKEN_COOKIE, "valid-refresh"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() != null);
        List<String> setCookies = response.getHeaders("Set-Cookie");
        assertEquals(2, setCookies.size());
        assertTrue(setCookies.stream().anyMatch(c -> c.contains("jcloud_access_token=new-access")));
        assertTrue(setCookies.stream().anyMatch(c -> c.contains("jcloud_refresh_token=new-refresh")));
        CurrentUser currentUser = (CurrentUser) request.getAttribute(CurrentUser.class.getName());
        assertEquals("1", currentUser.id());
        assertEquals("user", currentUser.userCode());
    }

    /**
     * 过期 access cookie + 无 refresh cookie → 401，不触发轮换。
     */
    @Test
    void expiredAccessCookieWithoutRefreshCookieShouldReturn401() throws ServletException, IOException {
        when(jwtUtil.parseToken("expired-access")).thenThrow(
                new ExpiredJwtException(Jwts.header().build(), Jwts.claims().build(), "expired"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
        request.setCookies(new Cookie(AuthConstant.ACCESS_TOKEN_COOKIE, "expired-access"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        verify(authSessionSupport, never()).refresh(any());
    }

    /**
     * 过期 access cookie + 无效 refresh cookie（refresh 抛业务异常）→ 401。
     */
    @Test
    void expiredAccessCookieWithInvalidRefreshCookieShouldReturn401() throws ServletException, IOException {
        when(jwtUtil.parseToken("expired-access")).thenThrow(
                new ExpiredJwtException(Jwts.header().build(), Jwts.claims().build(), "expired"));
        when(authSessionSupport.refresh("bad-refresh"))
                .thenThrow(new BusinessException(ResultCode.UNAUTHORIZED, "登录状态已失效"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", LOGIN_PATH);
        request.setCookies(new Cookie(AuthConstant.ACCESS_TOKEN_COOKIE, "expired-access"),
                new Cookie(AuthConstant.REFRESH_TOKEN_COOKIE, "bad-refresh"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals(401, body.get("code").asInt());
        assertEquals("登录状态已失效", body.get("msg").asText());
    }

    /**
     * 有效 access cookie → 放行，不触发轮换、不额外下发 Set-Cookie。
     */
    @Test
    void validAccessCookieShouldNotTriggerRenewOrSetCookie() throws ServletException, IOException {
        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("valid-access")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn("1");
        when(jwtUtil.getUserCode(claims)).thenReturn("user");

        User user = new User();
        user.setId("1");
        user.setIsAdmin(1);
        when(userMapper.selectById("1")).thenReturn(user);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
        request.setCookies(new Cookie(AuthConstant.ACCESS_TOKEN_COOKIE, "valid-access"),
                new Cookie(AuthConstant.REFRESH_TOKEN_COOKIE, "valid-refresh"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals(0, response.getHeaders("Set-Cookie").size());
        verify(authSessionSupport, never()).refresh(any());
    }

    /**
     * 过期 header token（原生端）→ 401，不触发静默续期、无 Set-Cookie。
     */
    @Test
    void expiredHeaderTokenShouldNotRenewAndReturn401() throws ServletException, IOException {
        when(jwtUtil.parseToken("expired-token")).thenThrow(
                new ExpiredJwtException(Jwts.header().build(), Jwts.claims().build(), "expired"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
        request.addHeader("Authorization", "Bearer expired-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals(0, response.getHeaders("Set-Cookie").size());
        verify(authSessionSupport, never()).refresh(any());
    }

    /**
     * 两个串行过期请求（宽限期重放）应拿到同一新令牌对。
     */
    @Test
    void twoSerialExpiredRequestsShouldReturnSameNewPair() throws ServletException, IOException {
        when(jwtUtil.parseToken("expired-access")).thenThrow(
                new ExpiredJwtException(Jwts.header().build(), Jwts.claims().build(), "expired"));
        // 模拟宽限期重放：多次 refresh 返回同一新令牌对
        when(authSessionSupport.refresh("valid-refresh"))
                .thenReturn(new RefreshResult("new-access", "new-refresh"));
        Claims newClaims = mock(Claims.class);
        when(jwtUtil.parseToken("new-access")).thenReturn(newClaims);
        when(jwtUtil.getUserId(newClaims)).thenReturn("1");
        when(jwtUtil.getUserCode(newClaims)).thenReturn("user");

        User user = new User();
        user.setId("1");
        user.setIsAdmin(1);
        when(userMapper.selectById("1")).thenReturn(user);

        MockHttpServletResponse resp1 = new MockHttpServletResponse();
        filter.doFilter(requestWithExpiredCookies(), resp1, new MockFilterChain());
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        filter.doFilter(requestWithExpiredCookies(), resp2, new MockFilterChain());

        assertEquals(200, resp1.getStatus());
        assertEquals(200, resp2.getStatus());
        assertEquals(2, resp1.getHeaders("Set-Cookie").size());
        assertEquals(resp1.getHeaders("Set-Cookie"), resp2.getHeaders("Set-Cookie"));
    }

    /**
     * 黑名单中的有效 access（cookie 通道）→ 401「登录状态已失效」。
     */
    @Test
    void blacklistedAccessViaCookieShouldReturn401() throws ServletException, IOException {
        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("revoked-access")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn("1");
        when(jwtUtil.getUserCode(claims)).thenReturn("user");
        when(jwtUtil.getDeviceId(claims)).thenReturn("dev-1");
        when(claims.getIssuedAt()).thenReturn(new Date(1000L));
        when(authBlacklistSupport.isRevoked("1", "dev-1", 1000L)).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
        request.setCookies(new Cookie(AuthConstant.ACCESS_TOKEN_COOKIE, "revoked-access"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals(401, body.get("code").asInt());
        assertEquals("登录状态已失效", body.get("msg").asText());
    }

    /**
     * 黑名单中的有效 access（header 通道）→ 401「登录状态已失效」（双通道一视同仁）。
     */
    @Test
    void blacklistedAccessViaHeaderShouldReturn401() throws ServletException, IOException {
        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("revoked-token")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn("1");
        when(jwtUtil.getUserCode(claims)).thenReturn("user");
        when(jwtUtil.getDeviceId(claims)).thenReturn("dev-1");
        when(claims.getIssuedAt()).thenReturn(new Date(1000L));
        when(authBlacklistSupport.isRevoked("1", "dev-1", 1000L)).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", LOGIN_PATH);
        request.addHeader("Authorization", "Bearer revoked-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals("登录状态已失效", body.get("msg").asText());
    }

    /**
     * 未列入黑名单的设备令牌 → 放行，鉴权行为不受影响。
     */
    @Test
    void nonBlacklistedDeviceTokenShouldPass() throws ServletException, IOException {
        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("valid-token")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn("1");
        when(jwtUtil.getUserCode(claims)).thenReturn("user");
        when(jwtUtil.getDeviceId(claims)).thenReturn("dev-1");
        when(claims.getIssuedAt()).thenReturn(new Date(1000L));
        when(authBlacklistSupport.isRevoked("1", "dev-1", 1000L)).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", LOGIN_PATH);
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() != null);
    }

    /**
     * 同设备重新登录产生的新 token（iat 晚于吊销时间戳）→ 放行。
     * <p>
     * 过滤器负责将请求令牌的 iat 透传给黑名单判定；比较语义在 AuthBlacklistSupport 内完成，
     * 此处以 isRevoked 返回 false 模拟「新 token 晚于吊销时间戳」的放行分支。
     */
    @Test
    void sameDeviceNewerTokenAfterRevokeShouldPass() throws ServletException, IOException {
        long revokeAtMillis = 5000L;
        long newerIat = 6000L; // 晚于吊销时间戳
        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("newer-token")).thenReturn(claims);
        when(jwtUtil.getUserId(claims)).thenReturn("1");
        when(jwtUtil.getUserCode(claims)).thenReturn("user");
        when(jwtUtil.getDeviceId(claims)).thenReturn("dev-1");
        when(claims.getIssuedAt()).thenReturn(new Date(newerIat));
        // 模拟 AuthBlacklistSupport 的 iat 与吊销时间戳比较：新 token 放行
        when(authBlacklistSupport.isRevoked("1", "dev-1", newerIat))
                .thenAnswer(inv -> inv.<Long>getArgument(2) <= revokeAtMillis);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", LOGIN_PATH);
        request.addHeader("Authorization", "Bearer newer-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() != null);
        verify(authBlacklistSupport).isRevoked("1", "dev-1", newerIat);
    }

    private MockHttpServletRequest requestWithExpiredCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
        request.setCookies(new Cookie(AuthConstant.ACCESS_TOKEN_COOKIE, "expired-access"),
                new Cookie(AuthConstant.REFRESH_TOKEN_COOKIE, "valid-refresh"));
        return request;
    }

}
