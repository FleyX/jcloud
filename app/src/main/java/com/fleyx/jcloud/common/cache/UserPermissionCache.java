package com.fleyx.jcloud.common.cache;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户权限缓存。
 * <p>
 * 用于缓存已登录用户的角色 ID、资源编码及超级管理员标识，避免每次请求都查询数据库。
 * 默认缓存 5 分钟，角色变更后应调用 {@link #evict(String)} 清除对应用户缓存。
 */
@Component
public class UserPermissionCache {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final Map<String, CacheValue> cache = new ConcurrentHashMap<>();

    /**
     * 缓存值。
     *
     * @param roleIds       角色 ID 列表
     * @param resourceCodes 资源编码列表
     * @param superAdmin    是否为超级管理员
     * @param expireAt      过期时间戳（毫秒）
     */
    public record CacheValue(List<String> roleIds, List<String> resourceCodes, boolean superAdmin, long expireAt) {
    }

    /**
     * 写入缓存，使用默认过期时间。
     *
     * @param userId        用户 ID
     * @param roleIds       角色 ID 列表
     * @param resourceCodes 资源编码列表
     * @param superAdmin    是否为超级管理员
     */
    public void put(String userId, List<String> roleIds, List<String> resourceCodes, boolean superAdmin) {
        long expireAt = Instant.now().plus(DEFAULT_TTL).toEpochMilli();
        cache.put(userId, new CacheValue(roleIds, resourceCodes, superAdmin, expireAt));
    }

    /**
     * 读取缓存，过期自动清除。
     *
     * @param userId 用户 ID
     * @return 缓存值，不存在或已过期返回 null
     */
    public CacheValue get(String userId) {
        CacheValue value = cache.get(userId);
        if (value == null) {
            return null;
        }
        if (Instant.now().toEpochMilli() > value.expireAt()) {
            cache.remove(userId);
            return null;
        }
        return value;
    }

    /**
     * 清除指定用户缓存。
     *
     * @param userId 用户 ID
     */
    public void evict(String userId) {
        cache.remove(userId);
    }

    /**
     * 清除全部缓存。
     */
    public void evictAll() {
        cache.clear();
    }
}
