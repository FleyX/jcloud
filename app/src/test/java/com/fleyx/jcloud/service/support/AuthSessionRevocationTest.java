package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.dto.BatchUserStatusDto;
import com.fleyx.jcloud.model.dto.ChangePasswordDto;
import com.fleyx.jcloud.model.dto.TokenRefreshDto;
import com.fleyx.jcloud.model.dto.UserLoginDto;
import com.fleyx.jcloud.model.dto.UserRegisterDto;
import com.fleyx.jcloud.model.dto.UserStatusDto;
import com.fleyx.jcloud.model.dto.UserUpdateDto;
import com.fleyx.jcloud.model.vo.LoginVo;
import com.fleyx.jcloud.service.AuthService;
import com.fleyx.jcloud.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 登出与安全吊销接缝测试：logout/logout-all 语义、改密/重置/禁用/删除触发全量吊销。
 * <p>
 * 随机 username/deviceId 隔离：Redis 不随事务回滚，因此每个用例使用独立用户与设备。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthSessionRevocationTest {

    private static final String PASSWORD = "123456";

    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;

    @Test
    void logoutShouldRevokeOnlyCurrentDeviceSession() {
        String username = randomUsername("logoutdev");
        LoginVo[] vos = registerAndLoginTwoDevices(username);

        // 吊销设备 A 会话，设备 B 不受影响
        authService.logout(vos[0].getRefreshToken());
        assertRefreshUnauthorized(vos[0].getRefreshToken());
        assertRefreshOk(vos[1].getRefreshToken());
    }

    @Test
    void logoutWithUnknownTokenShouldBeNoOp() {
        // 伪造/已过期/空令牌：幂等 no-op，不报错
        assertDoesNotThrow(() -> authService.logout("fake-or-expired-token"));
        assertDoesNotThrow(() -> authService.logout(""));
        assertDoesNotThrow(() -> authService.logout(null));
    }

    @Test
    void logoutAllShouldRevokeAllDeviceSessions() {
        String username = randomUsername("logoutall");
        LoginVo[] vos = registerAndLoginTwoDevices(username);

        authService.logoutAll(vos[0].getUserInfo().getId());
        assertRefreshUnauthorized(vos[0].getRefreshToken());
        assertRefreshUnauthorized(vos[1].getRefreshToken());
    }

    @Test
    void changePasswordShouldRevokeAllSessions() {
        String username = randomUsername("chgpwd");
        LoginVo[] vos = registerAndLoginTwoDevices(username);
        String userId = vos[0].getUserInfo().getId();

        ChangePasswordDto dto = new ChangePasswordDto();
        dto.setCurrentPassword(PASSWORD);
        dto.setNewPassword("newpass123");
        dto.setConfirmPassword("newpass123");
        userService.changePassword(userId, dto);

        assertRefreshUnauthorized(vos[0].getRefreshToken());
        assertRefreshUnauthorized(vos[1].getRefreshToken());
        // 旧密码无法登录，新密码可登录
        UserLoginDto oldLogin = new UserLoginDto();
        oldLogin.setUsername(username);
        oldLogin.setPassword(PASSWORD);
        assertThrows(BusinessException.class, () -> authService.login(oldLogin));
        UserLoginDto newLogin = new UserLoginDto();
        newLogin.setUsername(username);
        newLogin.setPassword("newpass123");
        assertNotNull(authService.login(newLogin).getToken());
    }

    @Test
    void adminResetPasswordShouldRevokeAllSessions() {
        String username = randomUsername("resetpwd");
        LoginVo[] vos = registerAndLoginTwoDevices(username);

        UserUpdateDto update = new UserUpdateDto();
        update.setId(vos[0].getUserInfo().getId());
        update.setPassword("adminreset123");
        userService.updateUser(update);

        assertRefreshUnauthorized(vos[0].getRefreshToken());
        assertRefreshUnauthorized(vos[1].getRefreshToken());
    }

    @Test
    void disableUserShouldRevokeAllSessions() {
        String username = randomUsername("disable");
        LoginVo[] vos = registerAndLoginTwoDevices(username);

        UserStatusDto dto = new UserStatusDto();
        dto.setUserId(vos[0].getUserInfo().getId());
        dto.setStatus(0);
        userService.updateStatus(dto);

        assertRefreshUnauthorized(vos[0].getRefreshToken());
        assertRefreshUnauthorized(vos[1].getRefreshToken());
        // 禁用后无法再登录
        UserLoginDto login = new UserLoginDto();
        login.setUsername(username);
        login.setPassword(PASSWORD);
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(login));
        assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getResultCode().getCode());
    }

    @Test
    void enableUserShouldNotRevokeSessions() {
        String username = randomUsername("enable");
        registerUser(username);
        LoginVo vo = loginDevice(username, "a");

        // 对已是启用状态的用户执行启用，不触发吊销
        UserStatusDto dto = new UserStatusDto();
        dto.setUserId(vo.getUserInfo().getId());
        dto.setStatus(1);
        userService.updateStatus(dto);

        assertRefreshOk(vo.getRefreshToken());
    }

    @Test
    void batchDisableShouldRevokeAllSessions() {
        String username = randomUsername("batchdis");
        LoginVo[] vos = registerAndLoginTwoDevices(username);

        BatchUserStatusDto dto = new BatchUserStatusDto();
        dto.setUserIds(List.of(vos[0].getUserInfo().getId()));
        dto.setStatus(0);
        userService.batchUpdateStatus(dto);

        assertRefreshUnauthorized(vos[0].getRefreshToken());
        assertRefreshUnauthorized(vos[1].getRefreshToken());
    }

    @Test
    void removeByIdShouldRevokeAllSessions() {
        String username = randomUsername("remove");
        LoginVo[] vos = registerAndLoginTwoDevices(username);

        userService.removeById(vos[0].getUserInfo().getId());

        assertRefreshUnauthorized(vos[0].getRefreshToken());
        assertRefreshUnauthorized(vos[1].getRefreshToken());
    }

    @Test
    void batchDeleteShouldRevokeAllSessions() {
        String username = randomUsername("batchdel");
        LoginVo[] vos = registerAndLoginTwoDevices(username);

        userService.batchDelete(List.of(vos[0].getUserInfo().getId()));

        assertRefreshUnauthorized(vos[0].getRefreshToken());
        assertRefreshUnauthorized(vos[1].getRefreshToken());
    }

    private String randomUsername(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * 注册随机用户并在两台设备登录，返回两台设备的登录结果。
     */
    private LoginVo[] registerAndLoginTwoDevices(String username) {
        registerUser(username);
        return new LoginVo[]{
                loginDevice(username, "a"),
                loginDevice(username, "b"),
        };
    }

    /**
     * 注册随机用户。
     */
    private void registerUser(String username) {
        UserRegisterDto reg = new UserRegisterDto();
        reg.setUsername(username);
        reg.setPassword(PASSWORD);
        reg.setEmail(username + "@example.com");
        reg.setNickname("昵称");
        authService.register(reg);
    }

    /**
     * 指定用户以随机设备标识登录。
     */
    private LoginVo loginDevice(String username, String deviceTag) {
        UserLoginDto login = new UserLoginDto();
        login.setUsername(username);
        login.setPassword(PASSWORD);
        login.setDeviceId("dev-" + deviceTag + "-" + UUID.randomUUID().toString().substring(0, 8));
        return authService.login(login, null);
    }

    /**
     * 断言指定刷新令牌刷新成功（会话仍有效）。
     */
    private void assertRefreshOk(String refreshToken) {
        TokenRefreshDto dto = new TokenRefreshDto();
        dto.setRefreshToken(refreshToken);
        assertNotNull(authService.refresh(dto).getToken());
    }

    /**
     * 断言指定刷新令牌刷新返回 401（会话已吊销）。
     */
    private void assertRefreshUnauthorized(String refreshToken) {
        TokenRefreshDto dto = new TokenRefreshDto();
        dto.setRefreshToken(refreshToken);
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.refresh(dto));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), ex.getResultCode().getCode());
    }
}