package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.UserStatus;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.UserLoginDto;
import com.fleyx.jcloud.model.dto.UserRegisterDto;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.LoginVo;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
