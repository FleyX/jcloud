package com.fleyx.jcloud.service.support;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 通知告警去重支撑类（ADR 0039）。
 * <p>
 * 用于配额、容量等阈值类告警的防轰炸：同一告警对象（某用户配额 / 某存储空间容量）在
 * {@link #DEDUP_TTL} 内只允许发送一次。存储走 RedissonClient 而非 RedisTemplate
 * （redisson-spring-data 4.x 下 RedisTemplate 有已知缺陷，见 ADR 0025）。
 */
@Component
@RequiredArgsConstructor
public class NotificationAlertDedupSupport {

    /**
     * 去重 key 前缀。
     */
    public static final String KEY_PREFIX = "notification:alert:";

    /**
     * 去重窗口：同一告警对象 24 小时内只发一次。
     */
    public static final Duration DEDUP_TTL = Duration.ofHours(24);

    private final RedissonClient redissonClient;

    /**
     * 尝试标记告警已发送；24 小时内已标记过则返回 false。
     *
     * @param alertKey 告警对象标识，如 {@code quota:{userId}}、{@code capacity:{spaceId}}
     * @return true 首次标记（可发送），false 窗口内已发送过
     */
    public boolean tryMark(String alertKey) {
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + alertKey, StringCodec.INSTANCE);
        return bucket.setIfAbsent("1", DEDUP_TTL);
    }
}
