package com.fleyx.jcloud.filter;

import tools.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.cache.UserPermissionCache;
import com.fleyx.jcloud.common.enums.CommonStatus;
import com.fleyx.jcloud.common.permission.PermissionResolver;
import com.fleyx.jcloud.mapper.ResourceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.po.Resource;
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
 * 验证虚拟线程改造后，资源在构造时一次性加载，且权限校验逻辑正确。
 */
class AuthTokenFilterTest {

    private static final String PUBLIC_PATH = "/jcloud/api/public/ping";
    private static final String LOGIN_PATH = "/jcloud/api/auth/me";
    private static final String API_PATH = "/jcloud/api/files";

    private JwtUtil jwtUtil;
    private ObjectMapper objectMapper;
    private ResourceMapper resourceMapper;
    private UserMapper userMapper;
    private UserRoleMapper userRoleMapper;
    private UserPermissionCache userPermissionCache;
    private PermissionResolver permissionResolver;

    private AuthTokenFilter filter;

    @BeforeEach
    void setUp() {
        jwtUtil = mock(JwtUtil.class);
        objectMapper = new ObjectMapper();
        resourceMapper = mock(ResourceMapper.class);
        userMapper = mock(UserMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        userPermissionCache = new UserPermissionCache();
        permissionResolver = mock(PermissionResolver.class);

        List<Resource> resources = List.of(
                buildResource("GET:" + PUBLIC_PATH, "PUBLIC"),
                buildResource("GET:" + LOGIN_PATH, "LOGIN"),
                buildResource("GET:" + API_PATH, "API")
        );
        when(resourceMapper.selectList(any())).thenReturn(resources);

        filter = new AuthTokenFilter(jwtUtil, objectMapper, resourceMapper,
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
        when(jwtUtil.getUserId(claims)).thenReturn(1L);
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
        when(jwtUtil.getUserId(claims)).thenReturn(1L);
        when(jwtUtil.getUserCode(claims)).thenReturn("user");

        User user = new User();
        user.setId(1L);
        user.setIsAdmin(0);
        when(userMapper.selectById(1L)).thenReturn(user);
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
        when(jwtUtil.getUserId(claims)).thenReturn(1L);
        when(jwtUtil.getUserCode(claims)).thenReturn("user");

        User user = new User();
        user.setId(1L);
        user.setIsAdmin(0);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(permissionResolver.resolveResourceCodes(any())).thenReturn(List.of());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
    }

    private Resource buildResource(String code, String type) {
        Resource resource = new Resource();
        resource.setCode(code);
        resource.setType(type);
        resource.setStatus(CommonStatus.ENABLED.getCode());
        return resource;
    }
}
