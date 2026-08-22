package com.fleyx.jcloud.service.support;

import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AuthBlacklistSupport 黑名单读写测试。
 * <p>
 * 覆盖 iat 与吊销时间戳的边界比较、TTL 设置与多设备隔离（真实 Redis，数据库 test 库）。
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthBlacklistSupportTest {

    @Autowired
    private AuthBlacklistSupport authBlacklistSupport;

    @Autowired
    private RedissonClient redissonClient;

    @Test
    void notRevokedDeviceShouldNotBeBlacklisted() {
        assertFalse(authBlacklistSupport.isRevoked("u-no", "d-no", System.currentTimeMillis()));
    }

    @Test
    void revokedDeviceShouldBlockTokensIssuedAtOrBeforeRevoke() {
        authBlacklistSupport.revoke("u1", "d1");
        long revokedAt = readTimestamp("u1", "d1");
        // 签发时间不晚于吊销时间戳的残余 access 被拦截
        assertTrue(authBlacklistSupport.isRevoked("u1", "d1", revokedAt));
        assertTrue(authBlacklistSupport.isRevoked("u1", "d1", revokedAt - 1000));
        // 同设备重新登录后的新 token 签发时间晚于吊销时间戳 → 放行
        assertFalse(authBlacklistSupport.isRevoked("u1", "d1", revokedAt + 1000));
    }

    @Test
    void revokeShouldSetTtlAndEntry() {
        authBlacklistSupport.revoke("u1", "d2");
        RBucket<String> bucket = redissonClient.getBucket("jcloud:auth:blacklist:u1:d2", StringCodec.INSTANCE);
        assertNotNull(bucket.get());
        assertEquals(Long.toString(readTimestamp("u1", "d2")), bucket.get());
        assertTrue(bucket.remainTimeToLive() > 0);
    }

    @Test
    void revokeShouldNotAffectOtherDevice() {
        authBlacklistSupport.revoke("u1", "d1");
        assertFalse(authBlacklistSupport.isRevoked("u1", "d2", System.currentTimeMillis()));
        assertFalse(authBlacklistSupport.isRevoked("u2", "d1", System.currentTimeMillis()));
    }

    @Test
    void revokeWithBlankShouldBeNoOp() {
        authBlacklistSupport.revoke("", "d1");
        authBlacklistSupport.revoke("u1", "");
        authBlacklistSupport.revoke(null, null);
        assertFalse(authBlacklistSupport.isRevoked("u1", "d1", System.currentTimeMillis()));
    }

    private long readTimestamp(String userId, String deviceId) {
        RBucket<String> bucket = redissonClient.getBucket(
                "jcloud:auth:blacklist:" + userId + ":" + deviceId, StringCodec.INSTANCE);
        return Long.parseLong(bucket.get());
    }
}
