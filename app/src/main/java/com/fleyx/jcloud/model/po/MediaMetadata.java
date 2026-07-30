package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * TMDB 媒体元数据全局共享缓存实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_media_metadata")
public class MediaMetadata extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * TMDB 条目 ID，season/episode 类型为空。
     */
    private Long tmdbId;

    /**
     * 类型：movie / tv / season / episode。
     */
    private String mediaType;

    /**
     * 所属剧 TMDB ID，仅 season/episode 类型有效。
     */
    private Long seriesTmdbId;

    /**
     * 季号，仅 season/episode 类型有效。
     */
    private Integer seasonNo;

    /**
     * 集号，仅 episode 类型有效。
     */
    private Integer episodeNo;

    /**
     * 标题（中文）。
     */
    private String title;

    /**
     * 原始标题。
     */
    private String originalTitle;

    /**
     * 简介。
     */
    private String overview;

    /**
     * 海报图缓存相对路径。
     */
    private String posterPath;

    /**
     * 背景图缓存相对路径。
     */
    private String backdropPath;

    /**
     * 上映/首播日期。
     */
    private String releaseDate;

    /**
     * TMDB 评分。
     */
    private Double voteAverage;

    /**
     * 类型列表，逗号分隔。
     */
    private String genres;

    /**
     * 季数，仅电视剧有效。
     */
    private Integer seasonCount;

    /**
     * TMDB 原始响应 JSON。
     */
    private String rawJson;
}
