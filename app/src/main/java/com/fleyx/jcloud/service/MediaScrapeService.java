package com.fleyx.jcloud.service;

/**
 * 媒体库削刮服务：独立的元数据匹配操作，与扫描按目录互斥。
 */
public interface MediaScrapeService {

    /**
     * 提交异步削刮任务。同目录已有扫描/削刮任务在执行时抛出业务异常。
     *
     * @param directoryId 目录 ID
     * @param userId      用户 ID
     * @param force       是否强制重新削刮所有非手动修正条目；false 仅处理未匹配条目
     */
    void submitScrape(String directoryId, String userId, boolean force);

    /**
     * 同步执行削刮（当前线程），同目录已有任务在执行时抛出业务异常。
     *
     * @param directoryId 目录 ID
     * @param userId      用户 ID
     * @param force       是否强制重新削刮所有非手动修正条目
     */
    void scrape(String directoryId, String userId, boolean force);
}
