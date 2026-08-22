package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.config.AuthProperties;
import com.fleyx.jcloud.config.JwtProperties;
import com.fleyx.jcloud.model.dto.TokenRefreshDto;
import com.fleyx.jcloud.model.dto.UserLoginDto;
import com.fleyx.jcloud.model.dto.UserRegisterDto;
import com.fleyx.jcloud.model.vo.DeviceSessionVo;
import com.fleyx.jcloud.model.vo.LoginVo;
import com.fleyx.jcloud.model.vo.TokenPairVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.AuthService;
import com.fleyx.jcloud.service.support.AuthCookieSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证授权控制器单元测试（无 Spring 上下文，凭证透传与响应包装）。
 */
class AuthControllerTest {

    private final AuthService authService = mock(AuthService.class);

    private final AuthProperties authProperties = new AuthProperties();
    private final JwtProperties jwtProperties = new JwtProperties();
    private final AuthCookieSupport authCookieSupport = new AuthCookieSupport(authProperties, jwtProperties);

    private final AuthController controller = new AuthController(authService, authCookieSupport);

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void setUp() {
        UserContext.set(new CurrentUser("user-1", "user-1"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static List<String> setCookieHeaders(MvcResult result) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
    }

    private static String findCookie(List<String> cookies, String prefix) {
        return cookies.stream().filter(c -> c.startsWith(prefix)).findFirst().orElseThrow();
    }

    /**
     * POST /auth/register：注册 DTO 透传，返回 R.ok 包装的用户视图。
     */
    @Test
    void shouldRegister() throws Exception {
        UserRegisterDto expected = new UserRegisterDto();
        expected.setUsername("zhangsan");
        expected.setPassword("123456");
        expected.setEmail("zhang@example.com");
        expected.setNickname("张三");

        UserVo vo = new UserVo();
        vo.setId("u1");
        vo.setUsername("zhangsan");
        when(authService.register(eq(expected))).thenReturn(vo);

        mockMvc.perform(post("/jcloud/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"zhangsan\",\"password\":\"123456\","
                                + "\"email\":\"zhang@example.com\",\"nickname\":\"张三\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("u1"))
                .andExpect(jsonPath("$.data.username").value("zhangsan"));

        verify(authService).register(eq(expected));
    }

    /**
     * POST /auth/login：登录凭证 DTO 透传，返回 R.ok 包装的 LoginVo；响应带双 token 的 Set-Cookie 且 body 仍含令牌对。
     */
    @Test
    void shouldLogin() throws Exception {
        UserLoginDto expected = new UserLoginDto();
        expected.setUsername("admin");
        expected.setPassword("admin123");

        UserVo userInfo = new UserVo();
        userInfo.setId("u1");
        userInfo.setUsername("admin");
        LoginVo vo = new LoginVo();
        vo.setToken("jwt-token");
        vo.setRefreshToken("refresh-token");
        vo.setUserInfo(userInfo);
        vo.setResources(List.of("user:list", "user:view"));
        vo.setInitialized(true);
        when(authService.login(eq(expected), isNull())).thenReturn(vo);

        MvcResult result = mockMvc.perform(post("/jcloud/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").value("jwt-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.data.userInfo.username").value("admin"))
                .andExpect(jsonPath("$.data.resources.length()").value(2))
                .andExpect(jsonPath("$.data.initialized").value(true))
                .andReturn();

        verify(authService).login(eq(expected), isNull());

        List<String> cookies = setCookieHeaders(result);
        assertEquals(2, cookies.size());
        long accessMaxAge = jwtProperties.getExpireHours() * 3600;
        String access = findCookie(cookies, "jcloud_access_token=jwt-token");
        assertTrue(access.contains("Path=/"));
        assertTrue(access.contains("HttpOnly"));
        assertTrue(access.contains("SameSite=Strict"));
        assertTrue(access.contains("Max-Age=" + accessMaxAge));
        assertFalse(access.contains("Secure"), "Secure 默认关闭时不应出现");
        String refresh = findCookie(cookies, "jcloud_refresh_token=refresh-token");
        assertTrue(refresh.contains("Max-Age=" + (authProperties.getRefreshExpireDays() * 86400)));
        assertTrue(refresh.contains("HttpOnly"));
        assertTrue(refresh.contains("SameSite=Strict"));
    }

    /**
     * POST /auth/refresh：刷新令牌 DTO 透传，响应带新令牌对的 Set-Cookie，body 仍返回令牌对。
     */
    @Test
    void shouldRefresh() throws Exception {
        TokenRefreshDto dto = new TokenRefreshDto();
        dto.setRefreshToken("old-refresh");
        when(authService.refresh(eq(dto))).thenReturn(new TokenPairVo("new-jwt", "new-refresh"));

        MvcResult result = mockMvc.perform(post("/jcloud/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old-refresh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").value("new-jwt"))
                .andExpect(jsonPath("$.data.refreshToken").value("new-refresh"))
                .andReturn();

        verify(authService).refresh(eq(dto));

        List<String> cookies = setCookieHeaders(result);
        assertEquals(2, cookies.size());
        assertTrue(findCookie(cookies, "jcloud_access_token=new-jwt").contains("HttpOnly"));
        assertTrue(findCookie(cookies, "jcloud_refresh_token=new-refresh").contains("HttpOnly"));
    }

    /**
     * GET /auth/me：透传 UserContext 中的当前用户 ID，返回 R.ok 包装的登录信息。
     */
    @Test
    void shouldGetCurrentUser() throws Exception {
        LoginVo vo = new LoginVo();
        vo.setToken("jwt-token");
        when(authService.getCurrentUser("user-1")).thenReturn(vo);

        mockMvc.perform(get("/jcloud/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").value("jwt-token"));

        verify(authService).getCurrentUser(eq("user-1"));
    }

    /**
     * POST /auth/logout：body 携带刷新令牌时透传给 service，并返回两条 Max-Age=0 的清除 cookie。
     */
    @Test
    void shouldLogout() throws Exception {
        MvcResult result = mockMvc.perform(post("/jcloud/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        verify(authService).logout("refresh-token");

        assertClearCookies(result.getResponse());
    }

    /**
     * POST /auth/logout body 为空：从刷新令牌 cookie 取令牌完成吊销，且仍清除双 token cookie。
     */
    @Test
    void shouldLogoutFromRefreshCookieWhenBodyEmpty() throws Exception {
        MvcResult result = mockMvc.perform(post("/jcloud/api/auth/logout")
                        .cookie(new Cookie("jcloud_refresh_token", "refresh-from-cookie"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        verify(authService).logout("refresh-from-cookie");
        assertClearCookies(result.getResponse());
    }

    private void assertClearCookies(MockHttpServletResponse response) {
        List<String> cookies = response.getHeaders(HttpHeaders.SET_COOKIE);
        assertEquals(2, cookies.size());
        assertTrue(findCookie(cookies, "jcloud_access_token=").contains("Max-Age=0"));
        assertTrue(findCookie(cookies, "jcloud_refresh_token=").contains("Max-Age=0"));
    }

    /**
     * POST /auth/logout-all：以 UserContext 中的当前用户吊销全部设备会话，返回 R.ok。
     */
    @Test
    void shouldLogoutAll() throws Exception {
        mockMvc.perform(post("/jcloud/api/auth/logout-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(authService).logoutAll("user-1");
    }

    /**
     * GET /auth/devices：透传 UserContext 用户 ID 与 deviceId 参数，返回 R.ok 包装的设备列表。
     */
    @Test
    void shouldListDevices() throws Exception {
        DeviceSessionVo vo = new DeviceSessionVo();
        vo.setDeviceId("dev-1");
        vo.setDeviceName("Chrome · Windows");
        vo.setLastActiveTime(123456789L);
        vo.setCurrent(true);
        when(authService.listDevices("user-1", "dev-1")).thenReturn(List.of(vo));

        mockMvc.perform(get("/jcloud/api/auth/devices").param("deviceId", "dev-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].deviceId").value("dev-1"))
                .andExpect(jsonPath("$.data[0].deviceName").value("Chrome · Windows"))
                .andExpect(jsonPath("$.data[0].current").value(true));

        verify(authService).listDevices(eq("user-1"), eq("dev-1"));
    }

    /**
     * DELETE /auth/devices/{deviceId}：透传 UserContext 用户 ID 与路径设备标识，返回 R.ok。
     */
    @Test
    void shouldRevokeDevice() throws Exception {
        mockMvc.perform(delete("/jcloud/api/auth/devices/dev-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(authService).revokeDevice("user-1", "dev-1");
    }

    /**
     * POST /auth/login 缺密码：@Valid 校验失败，GlobalExceptionHandler 包装为 code=400。
     */
    @Test
    void shouldRejectLoginWithoutPassword() throws Exception {
        mockMvc.perform(post("/jcloud/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("密码不能为空")));
    }

    /**
     * POST /auth/login 登录失败：service 抛业务异常，包装为 R（body code=401）。
     */
    @Test
    void shouldWrapLoginFailureBusinessException() throws Exception {
        UserLoginDto dto = new UserLoginDto();
        dto.setUsername("admin");
        dto.setPassword("wrong");
        when(authService.login(eq(dto), isNull()))
                .thenThrow(new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误"));

        mockMvc.perform(post("/jcloud/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("用户名或密码错误"));

        verify(authService).login(eq(dto), isNull());
    }
}
