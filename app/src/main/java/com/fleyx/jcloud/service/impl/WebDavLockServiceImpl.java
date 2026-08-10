package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.service.WebDavLockService;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * WebDAV 锁管理服务实现。
 * <p>
 * 使用 Redis 存储 lock token，锁默认 1 小时过期。
 * 存储走 RedissonClient 而非 StringRedisTemplate：redisson-spring-data 适配层未实现
 * spring-data-redis 4.x 的 set(..., SetCondition, Expiration) 重载，带 TTL 的 set 会在
 * 默认方法间无限递归（StackOverflowError）。
 */
@Service
@RequiredArgsConstructor
public class WebDavLockServiceImpl implements WebDavLockService {

    private static final String LOCK_KEY_PREFIX = "jcloud:webdav:lock:";
    private static final Duration LOCK_TTL = Duration.ofHours(1);

    private final RedissonClient redissonClient;

    @Override
    public String lock(String userId, String path) {
        String token = UUID.randomUUID().toString();
        // setIfAbsent：仅当 key 不存在时写入并带 TTL；返回 false 表示资源已被锁定
        boolean acquired = bucket(buildKey(userId, path)).setIfAbsent(token, LOCK_TTL);
        return acquired ? token : null;
    }

    @Override
    public boolean unlock(String userId, String path, String token) {
        RBucket<String> bucket = bucket(buildKey(userId, path));
        String stored = bucket.get();
        if (stored == null || !stored.equals(token)) {
            return false;
        }
        return bucket.delete();
    }

    @Override
    public String buildLockDiscovery(String path, String token) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <D:prop xmlns:D="DAV:">
                  <D:lockdiscovery>
                    <D:activelock>
                      <D:locktype><D:write/></D:locktype>
                      <D:lockscope><D:exclusive/></D:lockscope>
                      <D:depth>infinity</D:depth>
                      <D:owner></D:owner>
                      <D:timeout>Second-3600</D:timeout>
                      <D:locktoken><D:href>opaquelocktoken:%s</D:href></D:locktoken>
                      <D:lockroot><D:href>%s</D:href></D:lockroot>
                    </D:activelock>
                  </D:lockdiscovery>
                </D:prop>
                """.formatted(token, escapeXml(path));
    }

    private RBucket<String> bucket(String key) {
        return redissonClient.getBucket(key, StringCodec.INSTANCE);
    }

    private String buildKey(String userId, String path) {
        return LOCK_KEY_PREFIX + userId + ":" + path;
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
