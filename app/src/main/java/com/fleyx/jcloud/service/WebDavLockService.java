package com.fleyx.jcloud.service;

/**
 * WebDAV 锁管理服务。
 */
public interface WebDavLockService {

    /**
     * 锁定资源。
     *
     * @param userId 用户 ID
     * @param path   资源路径
     * @return lock token
     */
    String lock(String userId, String path);

    /**
     * 解锁资源。
     *
     * @param userId 用户 ID
     * @param path   资源路径
     * @param token  lock token
     * @return 是否成功解锁
     */
    boolean unlock(String userId, String path, String token);

    /**
     * 生成 lock 响应 XML。
     *
     * @param path  资源路径
     * @param token lock token
     * @return XML 字符串
     */
    String buildLockDiscovery(String path, String token);
}
