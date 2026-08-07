package com.fleyx.jcloud.service;

/**
 * 媒体库削刮服务：独立的元数据匹配操作，与扫描按媒体库互斥。
 */
public interface MediaScrapeService {

    /**
     * 提交异步削刮任务。同库已有扫描/削刮任务在执行时抛出业务异常。
     *
     * @param directoryId 媒体库 ID
     * @param userId      用户 ID
     * @param force       是否强制重新削刮所有非手动修正条目；false 仅处理未匹配条目
     */
    void submitScrape(String directoryId, String userId, boolean force);

    /**
     * 同步执行削刮（当前线程），同库已有任务在执行时抛出业务异常。
     *
     * @param directoryId 媒体库 ID
     * @param userId      用户 ID
     * @param force       是否强制重新削刮所有非手动修正条目
     */
    void scrape(String directoryId, String userId, boolean force);

    /**
     * 单条刷新元数据（两模式，工单 06）：按元数据行的 owner 定位电影/剧集行同步执行，
     * 不走异步任务；季/集归属抛业务异常「请刷新所属剧集」。结束后重算电影/剧集完整性。
     *
     * @param metadataId 元数据行 ID（详情页入口传 metadataId）
     * @param userId     当前用户 ID（归属校验）
     * @param mode       刷新模式：missing 补齐缺失文本字段并校验图片/NFO 产物缺失则重建
     *                   （已匹配行复用元数据行、不覆盖已有字段，manual 行只做产物补回不改字段）；
     *                   force 重新拉取 TMDB 全量覆盖字段并全量替换图片/NFO 产物
     *                   （manual 行拒绝，抛业务异常「手动匹配的条目不支持强制刷新」）
     */
    void refreshItem(String metadataId, String userId, String mode);
}
