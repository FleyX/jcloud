package com.fleyx.jcloud.service;

/**
 * 媒体库扫描服务。
 */
public interface MediaScanService {

    /**
     * 异步触发一次目录扫描（增量）。
     *
     * @param directoryId 目录 ID
     * @param userId      用户 ID
     */
    void submitScan(String directoryId, String userId);

    /**
     * 异步触发一次目录扫描。
     *
     * @param directoryId 目录 ID
     * @param userId      用户 ID
     * @param force       是否强制全量重扫（忽略文件未变化的增量跳过）
     */
    void submitScan(String directoryId, String userId, boolean force);

    /**
     * 同步执行目录扫描（定时任务使用）。
     *
     * @param directoryId 目录 ID
     */
    void scan(String directoryId);

    /**
     * 请求中断目录正在进行的扫描（协作式，扫描循环逐文件检查）。
     *
     * @param directoryId 目录 ID
     */
    void requestCancel(String directoryId);
}
