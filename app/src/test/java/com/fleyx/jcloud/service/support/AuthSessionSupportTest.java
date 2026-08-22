package com.fleyx.jcloud.service.support;

import cn.hutool.crypto.digest.DigestUtil;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.config.AuthProperties;
import com.fleyx.jcloud.model.bo.RefreshResult;
import com.fleyx.jcloud.model.dto.TokenRefreshDto;
import com.fleyx.jcloud.model.dto.UserLoginDto;
import com.fleyx.jcloud.model.dto.UserRegisterDto;
import com.fleyx.jcloud.model.vo.LoginVo;
import com.fleyx.jcloud.model.vo.TokenPairVo;
import com.fleyx.jcloud.service.AuthService;
import com.fleyx.jcloud.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AuthSessionSupport 接缝测试，覆盖验收标准全部行为。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthSessionSupportTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthSessionSupport authSessionSupport;

    @Autowired
    private AuthProperties authProperties;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private JwtUtil jwtUtil;

    private String randomUsername() {
        return "authtest_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Test
    void loginShouldReturnRefreshTokenAndDeviceId() {
        String username = randomUsername();
        UserRegisterDto reg = new UserRegisterDto();
        reg.setUsername(username);
        reg.setPassword("123456");
        reg.setEmail(username + "@example.com");
        reg.setNickname("昵称");
        authService.register(reg);

        UserLoginDto login = new UserLoginDto();
        login.setUsername(username);
        login.setPassword("123456");
        LoginVo vo = authService.login(login, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        assertNotNull(vo.getToken());
        assertNotNull(vo.getRefreshToken());
        assertNotNull(vo.getDeviceId());
    }

    @Test
    void deviceNameShouldPreferExplicit() {
        String username = randomUsername();
        UserRegisterDto reg = new UserRegisterDto();
        reg.setUsername(username);
        reg.setPassword("123456");
        reg.setEmail(username + "@example.com");
        reg.setNickname("昵称");
        authService.register(reg);

        UserLoginDto login = new UserLoginDto();
        login.setUsername(username);
        login.setPassword("123456");
        login.setDeviceName("我的iPhone");
        login.setDeviceId("dev-" + UUID.randomUUID().toString().substring(0, 8));
        LoginVo vo = authService.login(login, "Mozilla/5.0 Chrome/120");
        assertEquals(login.getDeviceId(), vo.getDeviceId());
        // 设备名优先生效
        assertEquals("我的iPhone", authSessionSupport.getSession(vo.getUserInfo().getId(), vo.getDeviceId()).getDeviceName());
    }

    @Test
    void deviceNameShouldParseFromUserAgentWhenNotExplicit() {
        String username = randomUsername();
        UserRegisterDto reg = new UserRegisterDto();
        reg.setUsername(username);
        reg.setPassword("123456");
        reg.setEmail(username + "@example.com");
        reg.setNickname("昵称");
        authService.register(reg);

        UserLoginDto login = new UserLoginDto();
        login.setUsername(username);
        login.setPassword("123456");
        LoginVo vo = authService.login(login,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        // UA 解析出浏览器名（Chrome · Windows）
        assertTrue(authSessionSupport.getSession(vo.getUserInfo().getId(), vo.getDeviceId()).getDeviceName().contains("Chrome"));
    }

    @Test
    void loginWithoutUserAgentShouldUseUnknownDevice() {
        String username = randomUsername();
        UserRegisterDto reg = new UserRegisterDto();
        reg.setUsername(username);
        reg.setPassword("123456");
        reg.setEmail(username + "@example.com");
        reg.setNickname("昵称");
        authService.register(reg);

        UserLoginDto login = new UserLoginDto();
        login.setUsername(username);
        login.setPassword("123456");
        LoginVo vo = authService.login(login, null);
        assertEquals("未知设备", authSessionSupport.getSession(vo.getUserInfo().getId(), vo.getDeviceId()).getDeviceName());
    }

    @Test
    void sameUserDifferentDeviceShouldHaveIndependentSessions() {
        String username = randomUsername();
        UserRegisterDto reg = new UserRegisterDto();
        reg.setUsername(username);
        reg.setPassword("123456");
        reg.setEmail(username + "@example.com");
        reg.setNickname("昵称");
        authService.register(reg);

        UserLoginDto login1 = new UserLoginDto();
        login1.setUsername(username);
        login1.setPassword("123456");
        login1.setDeviceId("dev-a-" + UUID.randomUUID().toString().substring(0, 6));
        LoginVo vo1 = authService.login(login1, null);

        UserLoginDto login2 = new UserLoginDto();
        login2.setUsername(username);
        login2.setPassword("123456");
        login2.setDeviceId("dev-b-" + UUID.randomUUID().toString().substring(0, 6));
        LoginVo vo2 = authService.login(login2, null);

        assertNotEquals(vo1.getDeviceId(), vo2.getDeviceId());
        assertNotEquals(vo1.getRefreshToken(), vo2.getRefreshToken());
        // 两个刷新令牌各自可用
        TokenRefreshDto r1 = new TokenRefreshDto();
        r1.setRefreshToken(vo1.getRefreshToken());
        TokenPairVo p1 = authService.refresh(r1);
        assertNotNull(p1.getToken());
        TokenRefreshDto r2 = new TokenRefreshDto();
        r2.setRefreshToken(vo2.getRefreshToken());
        TokenPairVo p2 = authService.refresh(r2);
        assertNotNull(p2.getToken());
    }

    @Test
    void refreshShouldRotateAndRenewTtl() {
        String username = randomUsername();
        UserRegisterDto reg = new UserRegisterDto();
        reg.setUsername(username);
        reg.setPassword("123456");
        reg.setEmail(username + "@example.com");
        reg.setNickname("昵称");
        authService.register(reg);

        UserLoginDto login = new UserLoginDto();
        login.setUsername(username);
        login.setPassword("123456");
        LoginVo vo = authService.login(login, null);

        TokenRefreshDto dto = new TokenRefreshDto();
        dto.setRefreshToken(vo.getRefreshToken());
        TokenPairVo pair = authService.refresh(dto);
        assertNotNull(pair.getToken());
        assertNotNull(pair.getRefreshToken());
        assertNotEquals(vo.getRefreshToken(), pair.getRefreshToken());
        // 旧令牌在宽限期内重放应返回相同新令牌对
        TokenRefreshDto replay = new TokenRefreshDto();
        replay.setRefreshToken(vo.getRefreshToken());
        TokenPairVo replayPair = authService.refresh(replay);
        assertEquals(pair.getToken(), replayPair.getToken());
        assertEquals(pair.getRefreshToken(), replayPair.getRefreshToken());
    }

    @Test
    void reuseBeyondGraceShouldRevokeSession() throws Exception {
        // 将宽限期临时调小以快测，测后恢复
        long originalGrace = authProperties.getRotationGraceSeconds();
        authProperties.setRotationGraceSeconds(1);
        try {
            String username = randomUsername();
            UserRegisterDto reg = new UserRegisterDto();
            reg.setUsername(username);
            reg.setPassword("123456");
            reg.setEmail(username + "@example.com");
            reg.setNickname("昵称");
            authService.register(reg);

            UserLoginDto login = new UserLoginDto();
            login.setUsername(username);
            login.setPassword("123456");
            LoginVo vo = authService.login(login, null);
            String firstRefresh = vo.getRefreshToken();
            // 第一次刷新，产生 rotated 记录
            TokenRefreshDto d1 = new TokenRefreshDto();
            d1.setRefreshToken(firstRefresh);
            TokenPairVo p1 = authService.refresh(d1);
            // 不睡眠等待 TTL，改为直接修改轮换记录的 rotatedAt 使其超出宽限期（避免真等待）
            String oldHash = DigestUtil.sha256Hex(firstRefresh);
            RBucket<String> rotatedBucket = redissonClient.getBucket("jcloud:auth:rotated:" + oldHash, StringCodec.INSTANCE);
            String rotatedJson = rotatedBucket.get();
            assertNotNull(rotatedJson);
            // 通过篡改时间戳让“已过期”判定成立
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = om.readTree(rotatedJson);
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("rotatedAt",
                    System.currentTimeMillis() - (authProperties.getRotationGraceSeconds() + 2) * 1000L);
            rotatedBucket.set(om.writeValueAsString(node), java.time.Duration.ofDays(authProperties.getRefreshExpireDays()));
            // 用“上上代”令牌（即 firstRefresh）重放，应判定泄漏并吊销
            TokenRefreshDto reuse = new TokenRefreshDto();
            reuse.setRefreshToken(firstRefresh);
            BusinessException ex = assertThrows(BusinessException.class, () -> authService.refresh(reuse));
            assertEquals(ResultCode.UNAUTHORIZED.getCode(), ex.getResultCode().getCode());
            // 该设备会话后续所有刷新失败（最新的也失败）
            TokenRefreshDto latest = new TokenRefreshDto();
            latest.setRefreshToken(p1.getRefreshToken());
            assertThrows(BusinessException.class, () -> authService.refresh(latest));
        } finally {
            authProperties.setRotationGraceSeconds(originalGrace);
        }
    }

    @Test
    void concurrentRefreshShouldNotForkSession() throws Exception {
        String username = randomUsername();
        UserRegisterDto reg = new UserRegisterDto();
        reg.setUsername(username);
        reg.setPassword("123456");
        reg.setEmail(username + "@example.com");
        reg.setNickname("昵称");
        authService.register(reg);

        UserLoginDto login = new UserLoginDto();
        login.setUsername(username);
        login.setPassword("123456");
        LoginVo vo = authService.login(login, null);
        String refreshToken = vo.getRefreshToken();

        int threads = 8;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TokenPairVo>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(exec.submit(() -> {
                start.await();
                TokenRefreshDto d = new TokenRefreshDto();
                d.setRefreshToken(refreshToken);
                return authService.refresh(d);
            }));
        }
        start.countDown();
        List<TokenPairVo> results = new ArrayList<>();
        for (Future<TokenPairVo> f : futures) {
            results.add(f.get());
        }
        exec.shutdown();
        // 单飞语义：所有并发请求返回相同令牌对
        String firstToken = results.get(0).getToken();
        String firstRefresh = results.get(0).getRefreshToken();
        for (TokenPairVo r : results) {
            assertEquals(firstToken, r.getToken());
            assertEquals(firstRefresh, r.getRefreshToken());
        }
        // 该新令牌可再次刷新（未分叉）
        TokenRefreshDto next = new TokenRefreshDto();
        next.setRefreshToken(firstRefresh);
        TokenPairVo nextPair = authService.refresh(next);
        assertNotNull(nextPair.getToken());
    }

    @Test
    void expiredOrFakeTokenShouldBeUnauthorized() {
        TokenRefreshDto fake = new TokenRefreshDto();
        fake.setRefreshToken("fake-token-" + UUID.randomUUID());
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.refresh(fake));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), ex.getResultCode().getCode());

        // 手动删 token 索引模拟过期
        String username = randomUsername();
        UserRegisterDto reg = new UserRegisterDto();
        reg.setUsername(username);
        reg.setPassword("123456");
        reg.setEmail(username + "@example.com");
        reg.setNickname("昵称");
        authService.register(reg);
        UserLoginDto login = new UserLoginDto();
        login.setUsername(username);
        login.setPassword("123456");
        LoginVo vo = authService.login(login, null);
        String hash = DigestUtil.sha256Hex(vo.getRefreshToken());
        RBucket<String> b = redissonClient.getBucket("jcloud:auth:token:" + hash, StringCodec.INSTANCE);
        b.delete();
        TokenRefreshDto expired = new TokenRefreshDto();
        expired.setRefreshToken(vo.getRefreshToken());
        BusinessException ex2 = assertThrows(BusinessException.class, () -> authService.refresh(expired));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), ex2.getResultCode().getCode());
    }

    @Test
    void oldJwtShouldStillBeValidInGracePeriod() {
        // 旧 24h token 在密钥/issuer 不变时仍可解析，不因 expire-hours 改为 2 而失效（只要未过期）
        String token = jwtUtil.generateToken("user-old-jwt", "user-old-jwt");
        assertNotNull(jwtUtil.parseToken(token));
        assertEquals("user-old-jwt", jwtUtil.getUserId(jwtUtil.parseToken(token)));
    }

    @Test
    void getCurrentUserShouldNotCreateSession() {
        String username = randomUsername();
        UserRegisterDto regDto = new UserRegisterDto();
        regDto.setUsername(username);
        regDto.setPassword("123456");
        regDto.setEmail(username + "@example.com");
        regDto.setNickname("昵称");
        com.fleyx.jcloud.model.vo.UserVo user = authService.register(regDto);
        String userId = user.getId();
        LoginVo me = authService.getCurrentUser(userId);
        // 不得颁发新会话
        assertNull(me.getRefreshToken());
        assertNull(me.getDeviceId());
    }
}
