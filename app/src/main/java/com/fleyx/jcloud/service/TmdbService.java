package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.vo.TmdbSearchResultVo;

import java.util.List;
import java.util.Map;

/**
 * TMDB 元数据服务。
 * <p>
 * 元数据按用户隔离（ADR 0020）。新模型（issue #20/#21）：V2 方法返回未绑定 owner 的
 * 游离元数据行（t_media_metadata），由调用方经 {@code MediaMetadataSupport} 绑定 owner 后落库。
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
     * 下载 TMDB 图片字节：poster/still 等使用 w500，backdrop 使用 w1280。
     *
     * @param tmdbImagePath TMDB 图片路径（详情响应中的 poster_path/backdrop_path/still_path）
     * @param kind          图片用途：poster / backdrop / still
     * @return 图片字节，路径为空或下载失败返回 null
     */
    byte[] downloadArtwork(String tmdbImagePath, String kind);

    // ---------- 新模型（issue #20，t_media_metadata） ----------

    /**
     * 季与集派生结果：季元数据 + 季内全部集元数据（按集号索引）。
     * 均未绑定 owner、未落库，由调用方绑定 owner 后经 MediaMetadataSupport 落库。
     *
     * @param season    季元数据（游离）
     * @param episodes  集号 → 集元数据（游离）
     */
    record SeasonFetchV2(MediaMetadata season, Map<Integer, MediaMetadata> episodes) {
    }

    /**
     * 按 TMDB ID 拉取详情（新模型），返回未绑定 owner 的游离元数据行，未落库。
     *
     * @param userId    用户 ID
     * @param tmdbId    TMDB 条目 ID
     * @param mediaType 类型：movie / tv
     * @return 元数据（游离，source=tmdb）
     */
    MediaMetadata fetchDetailV2(String userId, Long tmdbId, String mediaType);

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
    MediaMetadata autoMatchV2(String userId, String mediaType, String title, Integer year);

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
}
