package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.dto.TokenRefreshDto;
import com.fleyx.jcloud.model.dto.UserLoginDto;
import com.fleyx.jcloud.model.dto.UserRegisterDto;
import com.fleyx.jcloud.model.vo.DeviceSessionVo;
import com.fleyx.jcloud.model.vo.LoginVo;
import com.fleyx.jcloud.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设备管理接缝测试：设备列表查询、当前设备标记、按设备踢出。
 * <p>
 * 随机 username/deviceId 隔离：Redis 不随事务回滚，因此每个用例使用独立用户与设备。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthDeviceManagementTest {

    private static final String PASSWORD = "123456";

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthSessionSupport authSessionSupport;

    @Test
    void listDevicesShouldReturnAllSessionsWithDeviceNameAndCurrentFlag() {
        String username = randomUsername("listdev");
        registerUser(username);
        LoginVo deviceA = loginDevice(username, "a");
        loginDevice(username, "b");
        loginDevice(username, "c");

        List<DeviceSessionVo> devices = authService.listDevices(deviceA.getUserInfo().getId(), deviceA.getDeviceId());
        assertEquals(3, devices.size());
        for (DeviceSessionVo vo : devices) {
            assertNotNull(vo.getDeviceName());
            assertTrue(vo.getLastActiveTime() > 0);
        }
        // 仅当前设备标记为 current
        devices.stream().filter(vo -> vo.getDeviceId().equals(deviceA.getDeviceId()))
                .forEach(vo -> assertTrue(vo.getCurrent()));
        devices.stream().filter(vo -> !vo.getDeviceId().equals(deviceA.getDeviceId()))
                .forEach(vo -> assertFalse(vo.getCurrent()));
    }

    @Test
    void listDevicesWithoutCurrentDeviceIdShouldMarkAllFalse() {
        String username = randomUsername("listnocur");
        registerUser(username);
        LoginVo deviceA = loginDevice(username, "a");
        loginDevice(username, "b");

        List<DeviceSessionVo> devices = authService.listDevices(deviceA.getUserInfo().getId(), null);
        assertEquals(2, devices.size());
        devices.forEach(vo -> assertFalse(vo.getCurrent()));
    }

    @Test
    void refreshShouldUpdateLastActiveTime() throws Exception {
        String username = randomUsername("refreshts");
        registerUser(username);
        LoginVo vo = loginDevice(username, "a");
        String userId = vo.getUserInfo().getId();
        long loginTime = authSessionSupport.getSession(userId, vo.getDeviceId()).getLastActiveTime();
        Thread.sleep(5);
        assertRefreshOk(vo.getRefreshToken());
        long activeTime = authSessionSupport.getSession(userId, vo.getDeviceId()).getLastActiveTime();
        assertTrue(activeTime > loginTime, "刷新后最近活跃时间应更新");
    }

    @Test
    void revokeDeviceShouldInvalidateOnlyThatDevice() {
        String username = randomUsername("revokepart");
        registerUser(username);
        LoginVo deviceA = loginDevice(username, "a");
        LoginVo deviceB = loginDevice(username, "b");
        LoginVo deviceC = loginDevice(username, "c");
        String userId = deviceA.getUserInfo().getId();

        authService.revokeDevice(userId, deviceB.getDeviceId());

        assertRefreshOk(deviceA.getRefreshToken());
        assertRefreshUnauthorized(deviceB.getRefreshToken());
        assertRefreshOk(deviceC.getRefreshToken());
        assertEquals(2, authService.listDevices(userId, null).size());
    }

    @Test
    void revokeCurrentDeviceShouldEqualLogout() {
        String username = randomUsername("revokeself");
        registerUser(username);
        LoginVo deviceA = loginDevice(username, "a");
        LoginVo deviceB = loginDevice(username, "b");
        String userId = deviceA.getUserInfo().getId();

        authService.revokeDevice(userId, deviceA.getDeviceId());

        assertRefreshUnauthorized(deviceA.getRefreshToken());
        assertRefreshOk(deviceB.getRefreshToken());
    }

    @Test
    void devicesShouldBeScopedToOwnUser() {
        String usernameA = randomUsername("userisoA");
        String usernameB = randomUsername("userisoB");
        registerUser(usernameA);
        registerUser(usernameB);
        LoginVo voA = loginDevice(usernameA, "a");
        LoginVo voB = loginDevice(usernameB, "b");
        String userIdA = voA.getUserInfo().getId();
        String userIdB = voB.getUserInfo().getId();

        // 甲设备列表不含乙会话
        List<DeviceSessionVo> listA = authService.listDevices(userIdA, voA.getDeviceId());
        assertEquals(1, listA.size());
        assertEquals(voA.getDeviceId(), listA.get(0).getDeviceId());

        // 甲踢乙的 deviceId 无效，乙会话仍在
        authService.revokeDevice(userIdA, voB.getDeviceId());
        assertEquals(1, authService.listDevices(userIdB, null).size());
        assertRefreshOk(voB.getRefreshToken());
    }

    private String randomUsername(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
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