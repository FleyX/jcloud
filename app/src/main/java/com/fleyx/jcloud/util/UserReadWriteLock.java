package com.fleyx.jcloud.util;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 用户级 Redisson 读写锁管理器。
 * <p>
 * 按用户隔离并发：同一用户的多个读操作可并发执行，写操作串行执行。
 * 锁键名为 {@code jcloud:user-lock:{userId}}，与迁移、文件写操作共享同一锁实例。
 */
@Component
public class UserReadWriteLock {

    private static final String LOCK_KEY_PREFIX = "jcloud:user-lock:";

    private final RedissonClient redissonClient;

    public UserReadWriteLock(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 获取指定用户的读锁。
     *
     * @param userId 用户 ID
     * @return 读锁
     */
    public RLock readLock(Long userId) {
        return redissonClient.getReadWriteLock(buildKey(userId)).readLock();
    }

    /**
     * 获取指定用户的写锁。
     *
     * @param userId 用户 ID
     * @return 写锁
     */
    public RLock writeLock(Long userId) {
        return redissonClient.getReadWriteLock(buildKey(userId)).writeLock();
    }

    private String buildKey(Long userId) {
        return LOCK_KEY_PREFIX + userId;
    }
}
