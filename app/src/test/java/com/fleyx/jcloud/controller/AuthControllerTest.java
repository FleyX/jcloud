package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.model.dto.UserLoginDto;
import com.fleyx.jcloud.model.dto.UserRegisterDto;
import com.fleyx.jcloud.model.vo.LoginVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证授权控制器单元测试（无 Spring 上下文，凭证透传与响应包装）。
 */
class AuthControllerTest {

    private final AuthService authService = mock(AuthService.class);

    private final AuthController controller = new AuthController(authService);

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
     * POST /auth/login：登录凭证 DTO 透传，返回 R.ok 包装的 LoginVo（token/用户/资源/初始化标记）。
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
        vo.setUserInfo(userInfo);
        vo.setResources(List.of("user:list", "user:view"));
        vo.setInitialized(true);
        when(authService.login(eq(expected), org.mockito.ArgumentMatchers.isNull())).thenReturn(vo);

        mockMvc.perform(post("/jcloud/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").value("jwt-token"))
                .andExpect(jsonPath("$.data.userInfo.username").value("admin"))
                .andExpect(jsonPath("$.data.resources.length()").value(2))
                .andExpect(jsonPath("$.data.initialized").value(true));

        verify(authService).login(eq(expected), org.mockito.ArgumentMatchers.isNull());
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
        when(authService.login(eq(dto), org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误"));

        mockMvc.perform(post("/jcloud/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("用户名或密码错误"));

        verify(authService).login(eq(dto), org.mockito.ArgumentMatchers.isNull());
    }
}
