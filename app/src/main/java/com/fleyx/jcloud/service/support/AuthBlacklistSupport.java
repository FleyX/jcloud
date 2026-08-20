package com.fleyx.jcloud.service.support;

import cn.hutool.core.util.StrUtil;
import com.fleyx.jcloud.config.JwtProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 访问令牌黑名单（踢出/登出/全量吊销后立即生效）。
 * <p>
 * Redis key 前缀沿用 {@code jcloud:auth:} 风格：{@code jcloud:auth:blacklist:{userId}:{deviceId}}。
 * 值为吊销时间戳（epoch milli），TTL 与会话内 JWT 有效期一致，黑名单随残余 access 自然过期自动清除。
 */
@Slf4j
@Component
public class AuthBlacklistSupport {

    private static final String KEY_PREFIX = "jcloud:auth:blacklist:";

    private final RedissonClient redissonClient;
    private final JwtProperties jwtProperties;

    public AuthBlacklistSupport(RedissonClient redissonClient, JwtProperties jwtProperties) {
        this.redissonClient = redissonClient;
        this.jwtProperties = jwtProperties;
    }

    /**
     * 将指定设备写入黑名单，TTL = JWT 有效期。
     *
     * @param userId   用户 ID
     * @param deviceId 设备标识
     */
    public void revoke(String userId, String deviceId) {
        if (StrUtil.isBlank(userId) || StrUtil.isBlank(deviceId)) {
            return;
        }
        RBucket<String> bucket = redissonClient.getBucket(buildKey(userId, deviceId), StringCodec.INSTANCE);
        Duration ttl = Duration.ofHours(jwtProperties.getExpireHours());
        bucket.set(String.valueOf(System.currentTimeMillis()), ttl);
        log.info("访问令牌加入黑名单 userId={}, deviceId={}", userId, deviceId);
    }

    /**
     * 判断指定设备在签发时间点是否已被列入黑名单。
     * <p>
     * 无条目 → false；有条目且 {@code issuedAtMillis <= 吊销时间戳} → true（同设备重新登录产生的
     * 新 token 签发时间晚于吊销时间戳，必然放行）。
     *
     * @param userId         用户 ID
     * @param deviceId       设备标识
     * @param issuedAtMillis 访问令牌签发时间（epoch milli）
     * @return 是否已被吊销
     */
    public boolean isRevoked(String userId, String deviceId, long issuedAtMillis) {
        if (StrUtil.isBlank(userId) || StrUtil.isBlank(deviceId)) {
            return false;
        }
        RBucket<String> bucket = redissonClient.getBucket(buildKey(userId, deviceId), StringCodec.INSTANCE);
        String value = bucket.get();
        if (value == null) {
            return false;
        }
        long revokedAt = Long.parseLong(value);
        return issuedAtMillis <= revokedAt;
    }

    private String buildKey(String userId, String deviceId) {
        return KEY_PREFIX + userId + ":" + deviceId;
    }
}
