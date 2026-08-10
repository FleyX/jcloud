package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.service.WebDavLockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebDavLockServiceImpl 集成测试。
 * <p>
 * 锁存于 Redis（key: jcloud:webdav:lock:{userId}:{path}），不参与数据库事务，
 * 故不加 @Transactional，由每个用例在 @AfterEach 中清理本类创建的锁。
 */
class WebDavLockServiceImplTest extends IntegrationTestBase {

    private static final String LOCK_KEY_PREFIX = "jcloud:webdav:lock:";

    /** 测试专属用户 ID，避免与其它测试在共享 Redis 上的 key 相互干扰 */
    private static final String USER_ID = "webdav-lock-test-" + UUID.randomUUID();

    @Autowired
    private WebDavLockService webDavLockService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    void cleanUpLocks() {
        Set<String> keys = stringRedisTemplate.keys(LOCK_KEY_PREFIX + USER_ID + ":*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    @Test
    void lockShouldStoreTokenInRedisWithTtl() {
        String path = "/docs/plan.txt";
        String token = webDavLockService.lock(USER_ID, path);

        assertNotNull(token);
        assertEquals(token, stringRedisTemplate.opsForValue().get(buildKey(USER_ID, path)));
        // 锁默认 1 小时过期，TTL 为正且不超过 3600 秒
        Long ttl = stringRedisTemplate.getExpire(buildKey(USER_ID, path));
        assertNotNull(ttl);
        assertTrue(ttl > 0, "锁应带正 TTL");
        assertTrue(ttl <= 3600, "锁 TTL 不应超过 1 小时");
    }

    @Test
    void lockShouldBeIsolatedBetweenDifferentPaths() {
        String pathA = "/a.txt";
        String pathB = "/b.txt";
        String tokenA = webDavLockService.lock(USER_ID, pathA);
        String tokenB = webDavLockService.lock(USER_ID, pathB);

        assertNotEquals(tokenA, tokenB);
        assertEquals(tokenA, stringRedisTemplate.opsForValue().get(buildKey(USER_ID, pathA)));
        assertEquals(tokenB, stringRedisTemplate.opsForValue().get(buildKey(USER_ID, pathB)));
        // 解锁 A 不影响 B
        assertTrue(webDavLockService.unlock(USER_ID, pathA, tokenA));
        assertFalse(webDavLockService.unlock(USER_ID, pathB, tokenA));
        assertTrue(webDavLockService.unlock(USER_ID, pathB, tokenB));
    }

    @Test
    void relockOnSamePathShouldReturnNullAndKeepOldToken() {
        // RFC 4918：已锁资源重复 LOCK 应拒绝；lock 返回 null 表示资源已被锁定，原 token 不被覆盖
        String path = "/docs/shared.txt";
        String first = webDavLockService.lock(USER_ID, path);
        String second = webDavLockService.lock(USER_ID, path);

        assertNull(second);
        // 原 token 未被覆盖，旧 token 仍可解锁
        assertEquals(first, stringRedisTemplate.opsForValue().get(buildKey(USER_ID, path)));
        assertTrue(webDavLockService.unlock(USER_ID, path, first));
    }

    @Test
    void relockAfterUnlockShouldAcquireNewToken() {
        // 解锁释放锁后，同一路径可再次成功加锁并获得新 token
        String path = "/docs/release.txt";
        String first = webDavLockService.lock(USER_ID, path);
        assertTrue(webDavLockService.unlock(USER_ID, path, first));

        String second = webDavLockService.lock(USER_ID, path);
        assertNotNull(second);
        assertNotEquals(first, second);
        assertEquals(second, stringRedisTemplate.opsForValue().get(buildKey(USER_ID, path)));
        assertTrue(webDavLockService.unlock(USER_ID, path, second));
    }

    @Test
    void unlockShouldRemoveLockWhenTokenMatches() {
        String path = "/photos/1.jpg";
        String token = webDavLockService.lock(USER_ID, path);

        assertTrue(webDavLockService.unlock(USER_ID, path, token));
        assertFalse(stringRedisTemplate.hasKey(buildKey(USER_ID, path)));
        // 锁已删除，再次解锁同一 token 返回 false
        assertFalse(webDavLockService.unlock(USER_ID, path, token));
    }

    @Test
    void unlockShouldReturnFalseWhenNoLockExists() {
        // 从未加锁的路径：解锁返回 false 而非抛异常
        assertFalse(webDavLockService.unlock(USER_ID, "/not/locked.txt", "any-token"));
    }

    @Test
    void unlockShouldReturnFalseWhenTokenMismatch() {
        String path = "/notes/idea.md";
        String token = webDavLockService.lock(USER_ID, path);

        // token 校验失败：锁保持不变，仍可用正确 token 解锁
        assertFalse(webDavLockService.unlock(USER_ID, path, "wrong-token"));
        assertEquals(token, stringRedisTemplate.opsForValue().get(buildKey(USER_ID, path)));
        assertTrue(webDavLockService.unlock(USER_ID, path, token));
    }

    @Test
    void unlockShouldReturnFalseWhenLockExpired() {
        String path = "/expired/file.txt";
        String token = webDavLockService.lock(USER_ID, path);
        // 模拟锁过期：将 TTL 置为 0，Redis 下次访问时视为已过期删除
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.expire(buildKey(USER_ID, path), Duration.ZERO)));

        assertFalse(webDavLockService.unlock(USER_ID, path, token));
        assertFalse(stringRedisTemplate.hasKey(buildKey(USER_ID, path)));
    }

    @Test
    void buildLockDiscoveryShouldRenderTokenAndPath() {
        String path = "/dav/u1/docs/plan.txt";
        String token = "abc-123";

        String xml = webDavLockService.buildLockDiscovery(path, token);

        assertTrue(xml.contains("<?xml version=\"1.0\" encoding=\"utf-8\"?>"));
        assertTrue(xml.contains("xmlns:D=\"DAV:\""));
        assertTrue(xml.contains("<D:locktype><D:write/></D:locktype>"));
        assertTrue(xml.contains("<D:lockscope><D:exclusive/></D:lockscope>"));
        assertTrue(xml.contains("<D:depth>infinity</D:depth>"));
        assertTrue(xml.contains("<D:timeout>Second-3600</D:timeout>"));
        assertTrue(xml.contains("<D:locktoken><D:href>opaquelocktoken:" + token + "</D:href></D:locktoken>"));
        assertTrue(xml.contains("<D:lockroot><D:href>" + path + "</D:href></D:lockroot>"));
    }

    @Test
    void buildLockDiscoveryShouldEscapeXmlSpecialCharsInPath() {
        String path = "/dav/a&b<c>d\"e'f.txt";
        String token = "t-1";

        String xml = webDavLockService.buildLockDiscovery(path, token);

        assertTrue(xml.contains("opaquelocktoken:" + token));
        // 五个 XML 特殊字符全部被转义
        assertTrue(xml.contains("<D:lockroot><D:href>/dav/a&amp;b&lt;c&gt;d&quot;e&apos;f.txt</D:href></D:lockroot>"));
        // 原文中裸的特殊字符不应出现在输出中
        assertFalse(xml.contains("<D:href>/dav/a&b<c>d\"e'f.txt</D:href>"));
    }

    @Test
    void buildLockDiscoveryWithNullPathShouldNotFail() {
        String xml = webDavLockService.buildLockDiscovery(null, "t-2");

        // null path 按空字符串处理，lockroot 的 href 为空
        assertTrue(xml.contains("<D:lockroot><D:href></D:href></D:lockroot>"));
        assertTrue(xml.contains("opaquelocktoken:t-2"));
    }

    private String buildKey(String userId, String path) {
        return LOCK_KEY_PREFIX + userId + ":" + path;
    }
}
