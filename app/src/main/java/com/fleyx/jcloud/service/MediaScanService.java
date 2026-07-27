package com.fleyx.jcloud.service;

/**
 * 媒体库扫描服务。
 */
public interface MediaScanService {

    /**
     * 异步触发一次目录扫描。
     *
     * @param directoryId 目录 ID
     * @param userId      用户 ID
     */
    void submitScan(String directoryId, String userId);

    /**
     * 同步执行目录扫描（定时任务使用）。
     *
     * @param directoryId 目录 ID
     */
    void scan(String directoryId);
}
