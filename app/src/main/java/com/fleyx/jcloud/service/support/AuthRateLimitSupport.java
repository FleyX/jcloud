package com.fleyx.jcloud.service.support;

import cn.hutool.core.util.StrUtil;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 认证限流组件（认证失败锁定 + 客户端 IP 维度限额），供登录/分享/WebDAV 三个认证面复用。
 * <p>
 * Redis key 前缀 {@code jcloud:auth:rl:}，失败计数/锁定/IP 计数各占一类键：
 * <ul>
 *   <li>{@code fail:{subject}}：连续失败计数（RAtomicLong），TTL 略长于锁定时长；</li>
 *   <li>{@code lock:{subject}}：锁定标记，TTL = 锁定时长，存在即拒绝认证；</li>
 *   <li>{@code ip:{epochMinute}:{clientIp}}：IP 固定窗口计数（RAtomicLong），TTL 1 分钟。</li>
 * </ul>
 * 主体（subject）为任意字符串，调用方需自行加面级前缀防命名空间碰撞：
 * 登录 {@code login:{username}}、分享 {@code share:{shareCode}}、WebDAV {@code webdav:{userCode}}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthRateLimitSupport {

    private static final String KEY_PREFIX = "jcloud:auth:rl:";
    private static final String FAIL_PREFIX = KEY_PREFIX + "fail:";
    private static final String LOCK_PREFIX = KEY_PREFIX + "lock:";
    private static final String IP_PREFIX = KEY_PREFIX + "ip:";

    /**
     * 失败计数键 TTL 相对锁定时长的缓冲，确保锁定期间计数键不提前过期。
     */
    private static final Duration FAIL_TTL_BUFFER = Duration.ofMinutes(1);

    /**
     * IP 固定窗口 TTL（窗口键内含 epoch 分钟）。
     */
    private static final Duration IP_WINDOW = Duration.ofMinutes(1);

    /**
     * 锁定/限流对外的统一文案。
     */
    private static final String LOCKED_MESSAGE = "尝试次数过多，请稍后再试";

    private final RedissonClient redissonClient;
    private final AuthProperties authProperties;

    /**
     * 校验主体是否允许发起认证请求：未锁定且客户端 IP 未超窗口限额（每次调用计入窗口）。
     * 登录/分享面使用；WebDAV 面每个请求都携带凭据，使用 {@link #assertWebDavAllowed}。
     *
     * @param subject  主体（任意字符串，调用方加面级前缀）
     * @param clientIp 客户端 IP（空白时跳过 IP 维度校验，仅测试直连场景）
     */
    public void assertAllowed(String subject, String clientIp) {
        try {
            assertIpAllowed(clientIp);
            if (isLocked(subject)) {
                throw new BusinessException(ResultCode.FORBIDDEN, LOCKED_MESSAGE);
            }
        } catch (BusinessException | SystemException e) {
            throw e;
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "认证限流校验失败", e);
        }
    }

    /**
     * WebDAV 变体的锁定检查：复用锁定分支，IP 窗口仅只读检查
     * （当前窗口计数已达上限则抛，不递增）——WebDAV 正常浏览的数十个带凭据请求
     * 不应消耗 IP 窗口额度，只有失败认证才计数（见 {@link #recordWebDavFailure}）。
     *
     * @param subject  主体（调用方加面级前缀）
     * @param clientIp 客户端 IP（空白时跳过 IP 维度校验）
     */
    public void assertWebDavAllowed(String subject, String clientIp) {
        try {
            assertIpWindowNotExceeded(clientIp);
            if (isLocked(subject)) {
                throw new BusinessException(ResultCode.FORBIDDEN, LOCKED_MESSAGE);
            }
        } catch (BusinessException | SystemException e) {
            throw e;
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "认证限流校验失败", e);
        }
    }

    /**
     * 记录一次认证失败：失败计数 +1（刷新 TTL），达阈值则写入锁定键（TTL = 锁定时长）。
     *
     * @param subject 主体
     */
    public void recordFailure(String subject) {
        if (StrUtil.isBlank(subject)) {
            return;
        }
        try {
            AuthProperties.RateLimit rateLimit = authProperties.getRateLimit();
            RAtomicLong counter = redissonClient.getAtomicLong(FAIL_PREFIX + subject);
            long failures = counter.incrementAndGet();
            counter.expire(rateLimit.getLockDuration().plus(FAIL_TTL_BUFFER));
            if (failures >= rateLimit.getMaxFailures()) {
                bucket(LOCK_PREFIX + subject).set("1", rateLimit.getLockDuration());
                log.info("认证失败次数达阈值，锁定主体 subject={}, failures={}", subject, failures);
            }
        } catch (SystemException e) {
            throw e;
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "记录认证失败计数失败", e);
        }
    }

    /**
     * 记录一次 WebDAV 失败认证：主体失败计数 +1（规则同 {@link #recordFailure}），
     * 并递增当前 IP 窗口计数。
     *
     * @param subject  主体
     * @param clientIp 客户端 IP（空白时跳过 IP 维度计数）
     */
    public void recordWebDavFailure(String subject, String clientIp) {
        recordFailure(subject);
        try {
            incrementIpWindow(clientIp);
        } catch (SystemException e) {
            throw e;
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "记录WebDAV失败计数失败", e);
        }
    }

    /**
     * 认证成功后清零失败计数（删除失败计数键；锁定键仅在认证成功路径不可达时存在，不处理）。
     *
     * @param subject 主体
     */
    public void recordSuccess(String subject) {
        if (StrUtil.isBlank(subject)) {
            return;
        }
        try {
            redissonClient.getAtomicLong(FAIL_PREFIX + subject).delete();
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "清除认证失败计数失败", e);
        }
    }

    private void assertIpAllowed(String clientIp) {
        long count = incrementIpWindow(clientIp);
        if (StrUtil.isNotBlank(clientIp) && count > authProperties.getRateLimit().getIpMaxPerMinute()) {
            throw new BusinessException(ResultCode.FORBIDDEN, LOCKED_MESSAGE);
        }
    }

    private void assertIpWindowNotExceeded(String clientIp) {
        if (StrUtil.isBlank(clientIp)) {
            return;
        }
        long windowMinute = System.currentTimeMillis() / 60_000L;
        RAtomicLong counter = redissonClient.getAtomicLong(IP_PREFIX + windowMinute + ":" + clientIp);
        if (counter.get() >= authProperties.getRateLimit().getIpMaxPerMinute()) {
            throw new BusinessException(ResultCode.FORBIDDEN, LOCKED_MESSAGE);
        }
    }

    /**
     * 递增当前 IP 窗口计数并刷新窗口 TTL，返回递增后的计数（空白 IP 返回 0）。
     */
    private long incrementIpWindow(String clientIp) {
        if (StrUtil.isBlank(clientIp)) {
            return 0L;
        }
        long windowMinute = System.currentTimeMillis() / 60_000L;
        RAtomicLong counter = redissonClient.getAtomicLong(IP_PREFIX + windowMinute + ":" + clientIp);
        long count = counter.incrementAndGet();
        counter.expire(IP_WINDOW);
        return count;
    }

    private boolean isLocked(String subject) {
        return StrUtil.isNotBlank(subject) && bucket(LOCK_PREFIX + subject).isExists();
    }

    private RBucket<String> bucket(String key) {
        return redissonClient.getBucket(key, StringCodec.INSTANCE);
    }
}
