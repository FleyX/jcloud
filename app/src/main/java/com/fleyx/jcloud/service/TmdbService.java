package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.vo.TmdbSearchResultVo;

import java.util.List;

/**
 * TMDB 元数据服务。
 */
public interface TmdbService {

    /**
     * 搜索 TMDB 条目。
     *
     * @param mediaType 类型：movie / tv
     * @param query     搜索关键词
     * @param year      年份，可为 null
     * @return 搜索结果列表
     */
    List<TmdbSearchResultVo> search(String mediaType, String query, Integer year);

    /**
     * 按 TMDB ID 获取或拉取元数据（全局缓存，存在即复用）。
     *
     * @param tmdbId    TMDB 条目 ID
     * @param mediaType 类型：movie / tv
     * @return 元数据
     */
    MediaMetadata getOrFetch(Long tmdbId, String mediaType);

    /**
     * 自动匹配元数据，返回匹配到的元数据，匹配失败返回 null。
     *
     * @param mediaType 类型：movie / tv
     * @param title     标题
     * @param year      年份，可为 null
     * @return 匹配到的元数据或 null
     */
    MediaMetadata autoMatch(String mediaType, String title, Integer year);

    /**
     * 获取或拉取指定剧某一季的季元数据（全局缓存），并顺带 upsert 该季所有集的元数据。
     *
     * @param seriesTmdbId 剧 TMDB ID
     * @param seasonNo     季号
     * @return 季元数据
     */
    MediaMetadata getOrFetchSeason(Long seriesTmdbId, Integer seasonNo);

    /**
     * 查找指定集的全局缓存元数据，未削刮过返回 null。
     *
     * @param seriesTmdbId 剧 TMDB ID
     * @param seasonNo     季号
     * @param episodeNo    集号
     * @return 集元数据或 null
     */
    MediaMetadata findEpisode(Long seriesTmdbId, Integer seasonNo, Integer episodeNo);

    /**
     * 按元数据 ID 重新从 TMDB 拉取并覆盖缓存（含海报与背景图）。
     *
     * @param metadataId 元数据 ID
     * @return 刷新后的元数据
     */
    MediaMetadata refresh(String metadataId);

    /**
     * 为缺失背景图的存量元数据后台补抓，未配置 API Key 时直接跳过。
     */
    void backfillMissingBackdrops();
}
