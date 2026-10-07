package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.UserStatus;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.config.AuthProperties;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.TokenRefreshDto;
import com.fleyx.jcloud.model.dto.UserLoginDto;
import com.fleyx.jcloud.model.dto.UserRegisterDto;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.LoginVo;
import com.fleyx.jcloud.model.vo.TokenPairVo;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证服务单元测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthServiceImplTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private AuthProperties authProperties;

    private UserRegisterDto buildRegisterDto(String username) {
        UserRegisterDto dto = new UserRegisterDto();
        dto.setUsername(username);
        dto.setPassword("123456");
        dto.setEmail(username + "@example.com");
        dto.setNickname("昵称" + username);
        return dto;
    }

    @Test
    void registerShouldCreateUser() {
        UserRegisterDto dto = buildRegisterDto("authregister");
        UserVo vo = authService.register(dto);
        assertNotNull(vo.getId());
        assertEquals(dto.getUsername(), vo.getUsername());
        assertEquals(dto.getEmail(), vo.getEmail());
    }

    @Test
    void registerShouldRejectWhenRegistrationDisabled() {
        // 测试配置默认开启注册，此处显式关闭以覆盖拒绝分支
        authProperties.setRegistrationEnabled(false);
        try {
            UserRegisterDto dto = buildRegisterDto("authregclosed");
            BusinessException ex = assertThrows(BusinessException.class, () -> authService.register(dto));
            assertEquals("当前未开放注册", ex.getMessage());
        } finally {
            authProperties.setRegistrationEnabled(true);
        }
    }

    @Test
    void registerShouldSucceedWhenRegistrationEnabled() {
        authProperties.setRegistrationEnabled(true);
        UserRegisterDto dto = buildRegisterDto("authregopen");
        UserVo vo = authService.register(dto);
        assertNotNull(vo.getId());
        assertEquals(dto.getUsername(), vo.getUsername());
    }

    @Test
    void loginWithValidCredentialsShouldReturnToken() {
        UserRegisterDto reg = buildRegisterDto("authlogin");
        authService.register(reg);

        UserLoginDto login = new UserLoginDto();
        login.setUsername(reg.getUsername());
        login.setPassword(reg.getPassword());
        LoginVo vo = authService.login(login);

        assertNotNull(vo.getToken());
        assertNotNull(vo.getUserInfo());
        assertEquals(reg.getUsername(), vo.getUserInfo().getUsername());
        assertNotNull(vo.getResources());
        assertTrue(vo.getResources().contains("VIEW:/files"));
        assertTrue(vo.getResources().contains("GET:/jcloud/api/files"));
    }

    /**
     * 存量 email 为空的用户：登录与刷新令牌均不受影响。
     */
    @Test
    void loginAndRefreshShouldSucceedForLegacyUserWithoutEmail() {
        UserRegisterDto reg = buildRegisterDto("authnoemail");
        reg.setEmail(null);
        UserVo user = authService.register(reg);
        assertNull(user.getEmail());

        UserLoginDto login = new UserLoginDto();
        login.setUsername(reg.getUsername());
        login.setPassword(reg.getPassword());
        LoginVo vo = authService.login(login);
        assertNotNull(vo.getToken());
        assertNotNull(vo.getRefreshToken());

        TokenRefreshDto refresh = new TokenRefreshDto();
        refresh.setRefreshToken(vo.getRefreshToken());
        TokenPairVo pair = authService.refresh(refresh);
        assertNotNull(pair.getToken());
        assertNotNull(pair.getRefreshToken());
    }

    @Test
    void loginWithWrongPasswordShouldThrowUnauthorized() {
        UserRegisterDto reg = buildRegisterDto("authwrongpwd");
        authService.register(reg);

        UserLoginDto login = new UserLoginDto();
        login.setUsername(reg.getUsername());
        login.setPassword("wrong-password");
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(login));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), ex.getResultCode().getCode());
    }

    @Test
    void loginShouldBeLockedAfterConsecutiveFailures() {
        UserRegisterDto reg = buildRegisterDto("authlock");
        authService.register(reg);
        int maxFailures = authProperties.getRateLimit().getMaxFailures();

        UserLoginDto wrong = new UserLoginDto();
        wrong.setUsername(reg.getUsername());
        wrong.setPassword("wrong-password");
        for (int i = 0; i < maxFailures; i++) {
            assertThrows(BusinessException.class, () -> authService.login(wrong, null, "10.55.1.1"));
        }

        // 达阈值后锁定：第 maxFailures+1 次即使密码正确也拒绝，文案明确
        UserLoginDto correct = new UserLoginDto();
        correct.setUsername(reg.getUsername());
        correct.setPassword(reg.getPassword());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(correct, null, "10.55.1.1"));
        assertEquals("尝试次数过多，请稍后再试", ex.getMessage());
    }

    @Test
    void loginWithDisabledUserShouldThrowForbidden() {
        // 注册真实用户后将其状态改为禁用，登录时命中 AuthServiceImpl 的禁用分支返回 FORBIDDEN
        UserRegisterDto reg = buildRegisterDto("authdisabled");
        UserVo user = authService.register(reg);

        User update = new User();
        update.setId(user.getId());
        update.setStatus(UserStatus.DISABLED.getCode());
        userMapper.updateById(update);

        UserLoginDto login = new UserLoginDto();
        login.setUsername(reg.getUsername());
        login.setPassword(reg.getPassword());
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(login));
        assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getResultCode().getCode());
    }

    @Test
    void getCurrentUserShouldReturnUserInfo() {
        UserRegisterDto reg = buildRegisterDto("authcurrent");
        UserVo user = authService.register(reg);

        LoginVo vo = authService.getCurrentUser(user.getId());
        assertNotNull(vo);
        assertEquals(reg.getUsername(), vo.getUserInfo().getUsername());
    }
}
