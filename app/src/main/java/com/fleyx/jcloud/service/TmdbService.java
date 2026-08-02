package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMetadataV2;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.vo.TmdbSearchResultVo;

import java.util.List;
import java.util.Map;

/**
 * TMDB 元数据服务。
 * <p>
 * 元数据按用户隔离（ADR 0020）：电影/剧按 (user_id, media_type, tmdb_id) 查找，
 * 季/集元数据通过季/集条目已有的 metadata_id 直接绑定。
 * 新模型（issue #20）：V2 方法返回未绑定 owner 的游离元数据行（t_media_metadata_v2），
 * 由调用方经 {@code MediaMetadataV2Support} 绑定 owner 后落库。
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

    // ---------- 新模型（issue #20，t_media_metadata_v2） ----------

    /**
     * 季与集派生结果：季元数据 + 季内全部集元数据（按集号索引）。
     * 均未绑定 owner、未落库，由调用方绑定 owner 后经 MediaMetadataV2Support 落库。
     *
     * @param season    季元数据（游离）
     * @param episodes  集号 → 集元数据（游离）
     */
    record SeasonFetchV2(MediaMetadataV2 season, Map<Integer, MediaMetadataV2> episodes) {
    }

    /**
     * 按 TMDB ID 拉取详情（新模型），返回未绑定 owner 的游离元数据行，未落库。
     *
     * @param userId    用户 ID
     * @param tmdbId    TMDB 条目 ID
     * @param mediaType 类型：movie / tv
     * @return 元数据（游离，source=tmdb）
     */
    MediaMetadataV2 fetchDetailV2(String userId, Long tmdbId, String mediaType);

    /**
     * 自动匹配元数据（新模型）：搜索 + 候选打分选优 + 拉取详情，
     * 返回未绑定 owner 的游离元数据行，未落库；匹配失败返回 null。
     *
     * @param userId    用户 ID
     * @param mediaType 类型：movie / tv
     * @param title     标题
     * @param year      年份，可为 null
     * @return 匹配到的元数据（游离）或 null
     */
    MediaMetadataV2 autoMatchV2(String userId, String mediaType, String title, Integer year);

    /**
     * 拉取整季数据（新模型）：一次请求返回季元数据与季内全部集元数据，
     * 均未绑定 owner、未落库；季号为空或拉取失败返回 null。
     *
     * @param userId       用户 ID
     * @param seriesTmdbId 剧 TMDB ID
     * @param seasonNo     季号
     * @return 季与集派生结果或 null
     */
    SeasonFetchV2 fetchSeasonV2(String userId, Long seriesTmdbId, Integer seasonNo);

    /**
     * 重新从 TMDB 拉取并覆盖元数据字段（新模型，仅 movie/series 归属可刷新，
     * local_nfo 来源或 tmdb_id 为空时不刷新、原样返回）。返回待落库的元数据。
     *
     * @param metadata 元数据行（已绑定 owner）
     * @return 刷新后的元数据行
     */
    MediaMetadataV2 refreshV2(MediaMetadataV2 metadata);
}
