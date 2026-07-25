package com.fleyx.jcloud.filter;

import tools.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.cache.UserPermissionCache;
import com.fleyx.jcloud.common.permission.PermissionRegistry;
import com.fleyx.jcloud.common.permission.PermissionResolver;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.ServletException;
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
 * 验证资源在构造时从权限注册表一次性加载，且权限校验逻辑正确。
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

}
