package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.service.WebDavLockService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * WebDAV 锁管理服务实现。
 * <p>
 * 使用 Redis 存储 lock token，锁默认 1 小时过期。
 */
@Service
@RequiredArgsConstructor
public class WebDavLockServiceImpl implements WebDavLockService {

    private static final String LOCK_KEY_PREFIX = "jcloud:webdav:lock:";
    private static final Duration LOCK_TTL = Duration.ofHours(1);

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public String lock(String userId, String path) {
        String token = UUID.randomUUID().toString();
        String key = buildKey(userId, path);
        stringRedisTemplate.opsForValue().set(key, token, LOCK_TTL);
        return token;
    }

    @Override
    public boolean unlock(String userId, String path, String token) {
        String key = buildKey(userId, path);
        String stored = stringRedisTemplate.opsForValue().get(key);
        if (stored == null || !stored.equals(token)) {
            return false;
        }
        return Boolean.TRUE.equals(stringRedisTemplate.delete(key));
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
