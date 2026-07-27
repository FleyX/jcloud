package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 剧集视图（电视剧海报墙卡片，按剧聚合）。
 */
@Data
public class MediaSeriesVo {

    /**
     * 剧名。
     */
    private String seriesName;

    /**
     * 匹配到的元数据 ID。
     */
    private String metadataId;

    /**
     * 展示标题。
     */
    private String title;

    /**
     * 海报图 URL。
     */
    private String posterUrl;

    /**
     * 首播日期。
     */
    private String releaseDate;

    /**
     * 评分。
     */
    private Double voteAverage;

    /**
     * 集数。
     */
    private Long episodeCount;

    /**
     * 匹配状态。
     */
    private String matchStatus;

    /**
     * 最近播放时间（用于排序）。
     */
    private LocalDateTime lastPlayTime;
}
