package com.fleyx.jcloud.model.vo;

import lombok.Data;

/**
 * TMDB 搜索结果视图。
 */
@Data
public class TmdbSearchResultVo {

    /**
     * TMDB 条目 ID。
     */
    private Long tmdbId;

    /**
     * 类型：movie / tv。
     */
    private String mediaType;

    /**
     * 标题。
     */
    private String title;

    /**
     * 原始标题。
     */
    private String originalTitle;

    /**
     * 上映/首播日期。
     */
    private String releaseDate;

    /**
     * 评分。
     */
    private Double voteAverage;

    /**
     * 简介。
     */
    private String overview;

    /**
     * 海报图地址（代理缓存后可用）。
     */
    private String posterUrl;
}
