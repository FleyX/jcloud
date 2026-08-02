package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.vo.TmdbSearchResultVo;

import java.util.List;

/**
 * TMDB 元数据服务。
 * <p>
 * 元数据按用户隔离（ADR 0020）：电影/剧按 (user_id, media_type, tmdb_id) 查找，
 * 季/集元数据通过季/集条目已有的 metadata_id 直接绑定。
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
     * 按 TMDB ID 获取或拉取元数据（按用户隔离，本用户已存在即复用）。
     *
     * @param userId    用户 ID
     * @param tmdbId    TMDB 条目 ID
     * @param mediaType 类型：movie / tv
     * @return 元数据
     */
    MediaMetadata getOrFetch(String userId, Long tmdbId, String mediaType);

    /**
     * 自动匹配元数据，返回匹配到的元数据，匹配失败返回 null。
     *
     * @param userId    用户 ID
     * @param mediaType 类型：movie / tv
     * @param title     标题
     * @param year      年份，可为 null
     * @return 匹配到的元数据或 null
     */
    MediaMetadata autoMatch(String userId, String mediaType, String title, Integer year);

    /**
     * 获取或拉取指定剧某一季的季元数据：季已绑定元数据时直接返回，
     * 否则从 TMDB 拉取新建（关联字段由调用方回写）。
     *
     * @param userId       用户 ID
     * @param seriesTmdbId 剧 TMDB ID
     * @param season       季记录（含季号与已有元数据关联）
     * @return 季元数据
     */
    MediaMetadata getOrFetchSeason(String userId, Long seriesTmdbId, MediaSeason season);

    /**
     * 获取或创建指定集条目的集元数据：条目已绑定集元数据时直接返回，
     * 否则从季元数据的 TMDB 原始响应中解析创建（关联字段由调用方回写）；
     * 季数据中不存在该集时返回 null。
     *
     * @param userId         用户 ID
     * @param episode        集条目（含集号与已有元数据关联）
     * @param seasonMetadata 所属季的元数据
     * @return 集元数据或 null
     */
    MediaMetadata getOrFetchEpisode(String userId, MediaItem episode, MediaMetadata seasonMetadata);

    /**
     * 按元数据 ID 重新从 TMDB 拉取并覆盖（仅 movie/tv 详情字段，图片写回为后续阶段）。
     *
     * @param metadataId 元数据 ID
     * @return 刷新后的元数据
     */
    MediaMetadata refresh(String metadataId);

    /**
     * 下载 TMDB 图片字节：poster/still 等使用 w500，backdrop 使用 w1280。
     *
     * @param tmdbImagePath TMDB 图片路径（详情响应中的 poster_path/backdrop_path/still_path）
     * @param kind          图片用途：poster / backdrop / still
     * @return 图片字节，路径为空或下载失败返回 null
     */
    byte[] downloadArtwork(String tmdbImagePath, String kind);
}
