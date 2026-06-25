package com.fleyx.jcloud.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 用户级写锁管理器。
 * <p>
 * 用于串行化同一用户的文件写操作，避免并发移动、复制、上传等导致目录状态不一致。
 */
public final class UserWriteLock {

    private static final ConcurrentHashMap<Long, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private UserWriteLock() {
    }

    /**
     * 获取指定用户的写锁。
     *
     * @param userId 用户 ID
     * @return 可重入锁
     */
    public static ReentrantLock getLock(Long userId) {
        return LOCKS.computeIfAbsent(userId, k -> new ReentrantLock());
    }
}
