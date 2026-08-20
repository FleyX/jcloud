package com.fleyx.jcloud.service.support;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.config.AuthProperties;
import com.fleyx.jcloud.model.bo.AuthSession;
import com.fleyx.jcloud.model.bo.RefreshResult;
import com.fleyx.jcloud.util.JwtUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 设备会话的 Redis 存储与轮换核心。
 * <p>
 * Redis key 前缀 {@code jcloud:auth:}，值用 JSON 字符串 + {@code RBucket<String>}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthSessionSupport {

    private static final String KEY_PREFIX = "jcloud:auth:";
    private static final String SESSION_PREFIX = KEY_PREFIX + "session:";
    private static final String TOKEN_PREFIX = KEY_PREFIX + "token:";
    private static final String ROTATED_PREFIX = KEY_PREFIX + "rotated:";
    private static final String LOCK_PREFIX = KEY_PREFIX + "lock:refresh:";

    private final RedissonClient redissonClient;
    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;
    private final JwtUtil jwtUtil;
    private final AuthBlacklistSupport authBlacklistSupport;

    /**
     * 创建设备会话并返回刷新令牌原文。
     *
     * @param userId     用户 ID
     * @param userCode   用户编码
     * @param deviceId   设备标识（空白时生成随机串）
     * @param deviceName 设备名
     * @return 刷新令牌原文
     */
    public String createSession(String userId, String userCode, String deviceId, String deviceName) {
        String resolvedDeviceId = StrUtil.isBlank(deviceId) ? RandomUtil.randomString(16) : deviceId;
        String refreshToken = RandomUtil.randomString(64);
        String tokenHash = DigestUtil.sha256Hex(refreshToken);
        long now = System.currentTimeMillis();
        AuthSession session = new AuthSession();
        session.setUserId(userId);
        session.setUserCode(userCode);
        session.setDeviceId(resolvedDeviceId);
        session.setDeviceName(deviceName);
        session.setTokenHash(tokenHash);
        session.setCreateTime(now);
        session.setLastActiveTime(now);
        String sessionKey = buildSessionKey(userId, resolvedDeviceId);
        Duration ttl = Duration.ofDays(authProperties.getRefreshExpireDays());
        // 同设备重复登录，清理旧令牌索引
        String existingJson = bucket(sessionKey).get();
        if (existingJson != null) {
            try {
                AuthSession existing = fromJson(existingJson, AuthSession.class);
                if (StrUtil.isNotBlank(existing.getTokenHash())) {
                    bucket(buildTokenKey(existing.getTokenHash())).delete();
                }
            } catch (Exception e) {
                log.warn("清理旧令牌索引失败 sessionKey={}", sessionKey, e);
            }
        }
        String sessionJson = toJson(session);
        bucket(sessionKey).set(sessionJson, ttl);
        bucket(buildTokenKey(tokenHash)).set(sessionKey, ttl);
        log.debug("创建设备会话 userId={}, deviceId={}", userId, resolvedDeviceId);
        return refreshToken;
    }

    /**
     * 解析已解析的设备标识（供登录流程在颁发会话前确定 deviceId，便于 LoginVo 回显）。
     *
     * @param deviceId 原始设备标识
     * @return 解析后的设备标识（空白时生成随机串）
     */
    public String resolveDeviceId(String deviceId) {
        return StrUtil.isBlank(deviceId) ? RandomUtil.randomString(16) : deviceId;
    }

    /**
     * 刷新令牌轮换入口，内部持分布式锁保证单飞。
     *
     * @param refreshToken 刷新令牌原文
     * @return 新令牌对
     */
    public RefreshResult refresh(String refreshToken) {
        if (StrUtil.isBlank(refreshToken)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "登录状态已失效");
        }
        String hash = DigestUtil.sha256Hex(refreshToken);
        String lockKey = LOCK_PREFIX + hash;
        RLock lock = redissonClient.getLock(lockKey);
        lock.lock();
        try {
            String tokenKey = buildTokenKey(hash);
            RBucket<String> tokenBucket = bucket(tokenKey);
            String sessionKey = tokenBucket.get();
            if (sessionKey != null) {
                // 命中正常会话，执行轮换
                RBucket<String> sessionBucket = bucket(sessionKey);
                String sessionJson = sessionBucket.get();
                if (sessionJson == null) {
                    // 会话已丢失，清理索引并视为失效
                    tokenBucket.delete();
                    throw new BusinessException(ResultCode.UNAUTHORIZED, "登录状态已失效");
                }
                AuthSession session = fromJson(sessionJson, AuthSession.class);
                String newRefreshToken = RandomUtil.randomString(64);
                String newHash = DigestUtil.sha256Hex(newRefreshToken);
                String newAccessToken = jwtUtil.generateToken(session.getUserId(), session.getUserCode(), session.getDeviceId());
                // 更新会话
                session.setTokenHash(newHash);
                session.setLastActiveTime(System.currentTimeMillis());
                String newSessionJson = toJson(session);
                Duration ttl = Duration.ofDays(authProperties.getRefreshExpireDays());
                sessionBucket.set(newSessionJson, ttl);
                // 旧索引删除，新索引写入
                tokenBucket.delete();
                bucket(buildTokenKey(newHash)).set(sessionKey, ttl);
                // 写轮换记录，按计划为 30 天 TTL（宽限期判定见文档 4.2 小节；此处保持与会话同 TTL）
                RotatedRecord rotated = new RotatedRecord();
                rotated.setSessionKey(sessionKey);
                rotated.setNewAccessToken(newAccessToken);
                rotated.setNewRefreshToken(newRefreshToken);
                rotated.setRotatedAt(System.currentTimeMillis());
                bucket(buildRotatedKey(hash)).set(toJson(rotated), ttl);
                log.debug("刷新轮换成功 sessionKey={}, oldHash={}", sessionKey, hash);
                return new RefreshResult(newAccessToken, newRefreshToken);
            }
            // 未命中，查轮换记录
            RBucket<String> rotatedBucket = bucket(buildRotatedKey(hash));
            String rotatedJson = rotatedBucket.get();
            if (rotatedJson != null) {
                RotatedRecord rotated = fromJson(rotatedJson, RotatedRecord.class);
                long now = System.currentTimeMillis();
                long elapsedSeconds = (now - rotated.getRotatedAt()) / 1000;
                if (elapsedSeconds <= authProperties.getRotationGraceSeconds()) {
                    log.debug("宽限期内重放返回相同令牌对 oldHash={}", hash);
                    return new RefreshResult(rotated.getNewAccessToken(), rotated.getNewRefreshToken());
                }
                // 超过宽限，判定泄漏并吊销该设备会话
                log.info("刷新令牌宽限期外复用，吊销会话 sessionKey={}, oldHash={}", rotated.getSessionKey(), hash);
                revokeSession(rotated.getSessionKey());
                throw new BusinessException(ResultCode.UNAUTHORIZED, "登录状态已失效");
            }
            throw new BusinessException(ResultCode.UNAUTHORIZED, "登录状态已失效");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 读取设备会话（供测试与后续设备列表使用），不存在时返回 null。
     *
     * @param userId   用户 ID
     * @param deviceId 设备标识
     * @return 会话记录
     */
    public AuthSession getSession(String userId, String deviceId) {
        String json = bucket(buildSessionKey(userId, deviceId)).get();
        return json == null ? null : fromJson(json, AuthSession.class);
    }

    /**
     * 按刷新令牌吊销对应设备会话。
     * <p>
     * 幂等设计：令牌已吊销/过期/伪造（token 索引未命中）时静默 no-op，登出不报错。
     *
     * @param refreshToken 刷新令牌原文
     */
    public void revokeByRefreshToken(String refreshToken) {
        if (StrUtil.isBlank(refreshToken)) {
            return;
        }
        try {
            String hash = DigestUtil.sha256Hex(refreshToken);
            String sessionKey = bucket(buildTokenKey(hash)).get();
            if (sessionKey != null) {
                revokeSession(sessionKey);
            }
            log.debug("按刷新令牌吊销会话 hash={}", hash);
        } catch (SystemException e) {
            throw e;
        } catch (Exception e) {
            // 吊销是安全语义，Redis 异常不静默吞掉，包装为 SystemException 带上原异常
            throw new SystemException(ResultCode.SYSTEM_ERROR, "吊销会话失败", e);
        }
    }

    /**
     * 吊销指定用户全部设备会话。
     * <p>
     * 通过 scan 模式枚举该用户全部会话 key 实现；个人网盘规模下会话数量有限，开销可接受。
     *
     * @param userId 用户 ID
     * @return 吊销的会话数量
     */
    public int revokeAllSessions(String userId) {
        if (StrUtil.isBlank(userId)) {
            return 0;
        }
        try {
            Iterable<String> keys = redissonClient.getKeys().getKeysByPattern(SESSION_PREFIX + userId + ":*");
            int count = 0;
            for (String sessionKey : keys) {
                revokeSession(sessionKey);
                count++;
            }
            log.info("全量吊销用户会话 userId={}, count={}", userId, count);
            return count;
        } catch (SystemException e) {
            throw e;
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "吊销用户全部会话失败", e);
        }
    }

    /**
     * 吊销设备会话，删除会话记录与当前令牌索引，并将该设备写入访问令牌黑名单。
     *
     * @param sessionKey 会话 key
     */
    public void revokeSession(String sessionKey) {
        if (StrUtil.isBlank(sessionKey)) {
            return;
        }
        RBucket<String> sessionBucket = bucket(sessionKey);
        String sessionJson = sessionBucket.get();
        if (sessionJson != null) {
            try {
                AuthSession session = fromJson(sessionJson, AuthSession.class);
                if (StrUtil.isNotBlank(session.getTokenHash())) {
                    bucket(buildTokenKey(session.getTokenHash())).delete();
                }
                // 残余访问令牌立即失效（踢出/登出/全量吊销/泄漏吊销均经此汇合点生效）
                authBlacklistSupport.revoke(session.getUserId(), session.getDeviceId());
            } catch (Exception e) {
                log.warn("吊销会话解析失败 sessionKey={}", sessionKey, e);
            }
        }
        sessionBucket.delete();
        log.debug("已吊销会话 sessionKey={}", sessionKey);
    }

    /**
     * 枚举指定用户全部有效设备会话，按最近活跃时间倒序排列。
     * <p>
     * 通过 scan 模式枚举该用户全部会话 key 实现（与 {@link #revokeAllSessions} 同款模式）。
     *
     * @param userId 用户 ID
     * @return 会话列表
     */
    public List<AuthSession> listSessions(String userId) {
        if (StrUtil.isBlank(userId)) {
            return Collections.emptyList();
        }
        try {
            Iterable<String> keys = redissonClient.getKeys().getKeysByPattern(SESSION_PREFIX + userId + ":*");
            List<AuthSession> sessions = new ArrayList<>();
            for (String sessionKey : keys) {
                String json = bucket(sessionKey).get();
                if (json != null) {
                    sessions.add(fromJson(json, AuthSession.class));
                }
            }
            sessions.sort(Comparator.comparingLong(AuthSession::getLastActiveTime).reversed());
            return sessions;
        } catch (SystemException e) {
            throw e;
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "查询用户会话列表失败", e);
        }
    }

    /**
     * 按用户与设备标识吊销设备会话（供设备管理踢出使用，仅能吊销该用户自己的会话）。
     * <p>
     * 会话 key 自带 userId 前缀，天然隔离；幂等设计：会话不存在时静默 no-op。
     *
     * @param userId   用户 ID
     * @param deviceId 设备标识
     */
    public void revokeSession(String userId, String deviceId) {
        if (StrUtil.isBlank(userId) || StrUtil.isBlank(deviceId)) {
            return;
        }
        revokeSession(buildSessionKey(userId, deviceId));
    }

    String buildSessionKey(String userId, String deviceId) {
        return SESSION_PREFIX + userId + ":" + deviceId;
    }

    private String buildTokenKey(String hash) {
        return TOKEN_PREFIX + hash;
    }

    private String buildRotatedKey(String hash) {
        return ROTATED_PREFIX + hash;
    }

    private RBucket<String> bucket(String key) {
        return redissonClient.getBucket(key, StringCodec.INSTANCE);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "反序列化失败", e);
        }
    }

    /**
     * 轮换记录内部对象。
     */
    @Data
    static class RotatedRecord {
        private String sessionKey;
        private String newAccessToken;
        private String newRefreshToken;
        private long rotatedAt;
    }
}
