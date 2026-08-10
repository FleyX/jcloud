package com.fleyx.jcloud.service;

/**
 * WebDAV 锁管理服务。
 */
public interface WebDavLockService {

    /**
     * 锁定资源。
     * <p>
     * 仅当该路径当前没有锁时写入新锁并返回新 token；若资源已被锁定则返回 {@code null}，
     * 调用方应按 RFC 4918 返回 423 Locked。不支持携带 lock token 的 refresh lock。
     *
     * @param userId 用户 ID
     * @param path   资源路径
     * @return lock token；资源已被锁定（重复 LOCK）时返回 null
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
