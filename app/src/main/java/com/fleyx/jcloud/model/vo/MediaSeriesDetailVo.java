package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 电视剧详情视图（详情页）。
 */
@Data
public class MediaSeriesDetailVo {

    /**
     * 剧名。
     */
    private String seriesName;

    /**
     * 匹配状态。
     */
    private String matchStatus;

    /**
     * 元数据完整性标志（剧集行，聚合语义），未匹配无元数据时为 false。
     */
    private Boolean metadataComplete;

    /**
     * 元数据 ID。
     */
    private String metadataId;

    /**
     * 展示标题（元数据标题或剧名）。
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
     * 类型标签列表。
     */
    private List<String> genres;

    /**
     * 首播日期。
     */
    private String releaseDate;

    /**
     * 评分。
     */
    private Double voteAverage;

    /**
     * 季数。
     */
    private Integer seasonCount;

    /**
     * 海报图 URL。
     */
    private String posterUrl;

    /**
     * 背景图 URL。
     */
    private String backdropUrl;

    /**
     * 季列表（按季号升序，未知季排最后）。
     */
    private List<MediaSeriesSeasonVo> seasons;
}
