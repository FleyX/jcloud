package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.model.po.MediaMetadata;

/**
 * 削刮差异层（工单 09）：三型削刮共享骨架（{@link MediaScrapeDriverSupport}）之上的类型差异注入点。
 * <p>
 * 电影与剧集各提供一个实现：电影为文件名解析+文件夹名兜底匹配与单层匹配应用；剧集以剧为单位应用
 * （季/集按剧级匹配派生，深层逻辑委托 {@link MediaTvScrapeSupport}）。OTHER 库永不削刮，无实现。
 */
public interface ScrapeStrategy<Row> {

    /** 按 ownerId 加载削刮目标行；不存在返回 null（调用方抛业务异常）。 */
    Row loadRow(String ownerId);

    /** 行不存在时业务异常提示的实体名（"电影"/"电视剧"）。 */
    String entityName();

    /** 元数据类型编码（{@link com.fleyx.jcloud.common.enums.MediaType} MOVIE/TV 的 code）。 */
    String mediaTypeCode();

    /** 行所属媒体库目录 ID。 */
    String directoryIdOf(Row row);

    /** 行所属用户 ID。 */
    String userIdOf(Row row);

    /** 行关联的元数据行 ID（未匹配时为 null）。 */
    String metadataIdOf(Row row);

    /** 是否 manual 匹配（manual 恒豁免字段覆盖，仅产物补回）。 */
    boolean isManual(Row row);

    /** 是否已匹配（matched/manual 均视为已匹配）。 */
    boolean isMatched(Row row);

    /**
     * 本地优先削刮：解析本地 NFO/图片并绑定 owner 返回元数据行；无 NFO 且无本地图片返回 null。
     * 剧集实现含季海报与逐集 NFO 绑定（委托 {@link MediaTvScrapeSupport#scrapeSeriesLocalNfo}）。
     */
    MediaMetadata scrapeLocalNfo(Row row);

    /**
     * 本地元数据 TMDB 补全（ADR 0023 合并语义）：本地字段优先、缺失字段由远端补齐、rawJson 恒取远端。
     * 电影实现无 tmdbId 时经文件名解析+文件夹名兜底自动匹配补文本；剧集实现匹配失败维持本地。
     */
    MediaMetadata enrichLocal(Row row, MediaMetadata local);

    /** TMDB 自动匹配（本地无内容时）：电影按视频文件名解析结果、失败用电影文件夹名兜底；剧集按剧名+年份。 */
    MediaMetadata autoMatch(Row row);

    /**
     * force 通道拉取：有既有元数据且 tmdbId 非空按 ID 重新拉详情，否则回退 {@link #autoMatch}；
     * 拉取失败返回 null（调用方回退本地优先主流程，不清空既有匹配）。
     */
    MediaMetadata pullByTmdbId(Row row);

    /**
     * 应用匹配结果并绑定 owner，返回绑定后的元数据行（写回与完整性重算用）；电影为单层绑定，
     * 剧集含季/集派生（委托 {@link MediaTvScrapeSupport#bindSeriesMatch}）。
     * 驱动约定未匹配走 {@link #applyUnmatch}，本方法不接 null。
     */
    MediaMetadata applyMatch(Row row, MediaMetadata metadata, boolean force);

    /** 应用匹配失败：清空元数据行置未匹配（电影单层；剧集清剧/季/集三级）并重算完整性。 */
    void applyUnmatch(Row row);

    /** 写回 NFO/图片产物；任一步失败仅标记 persist_status=failed，不影响削刮主流程。 */
    void persist(Row row, MediaMetadata bound, boolean force);

    /** 削刮结束后重算行元数据完整性。 */
    void refreshComplete(Row row);
}
