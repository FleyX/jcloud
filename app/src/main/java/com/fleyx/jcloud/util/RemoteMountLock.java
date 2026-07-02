package com.fleyx.jcloud.util;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 远程挂载点级分布式锁。
 * <p>
 * 按挂载点 ID 隔离并发：同一挂载点的同步任务与远程写回操作串行执行。
 */
@Component
public class RemoteMountLock {

    private static final String LOCK_KEY_PREFIX = "jcloud:remote-mount-lock:";

    private final RedissonClient redissonClient;

    public RemoteMountLock(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 获取指定挂载点的锁。
     *
     * @param remoteMountId 远程挂载 ID
     * @return 锁
     */
    public RLock getLock(String remoteMountId) {
        return redissonClient.getLock(LOCK_KEY_PREFIX + remoteMountId);
    }
}
