package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 媒体条目详情视图（详情页）。
 */
@Data
public class MediaItemDetailVo {

    /**
     * 条目 ID。
     */
    private String id;

    /**
     * 条目类型：movie / episode / other。
     */
    private String itemType;

    /**
     * 文件名。
     */
    private String fileName;

    /**
     * 文件大小（字节）。
     */
    private Long fileSize;

    /**
     * 匹配状态。
     */
    private String matchStatus;

    /**
     * 元数据 ID。
     */
    private String metadataId;

    /**
     * 所属剧的元数据 ID，仅 episode 有效（用于详情页剧级背景图等）。
     */
    private String seriesMetadataId;

    /**
     * 所属剧名，仅 episode 有效。
     */
    private String seriesName;

    /**
     * 季号，仅 episode 有效。
     */
    private Integer seasonNo;

    /**
     * 集号，仅 episode 有效。
     */
    private Integer episodeNo;

    /**
     * 时长（毫秒）。
     */
    private Long durationMs;

    /**
     * 播放进度（毫秒）。
     */
    private Long progressMs;

    /**
     * 视频宽。
     */
    private Integer width;

    /**
     * 视频高。
     */
    private Integer height;

    /**
     * 视频编码。
     */
    private String videoCodec;

    /**
     * 音频编码。
     */
    private String audioCodec;

    /**
     * 展示标题（元数据标题或文件名）。
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
     * 上映/首播日期。
     */
    private String releaseDate;

    /**
     * 评分。
     */
    private Double voteAverage;

    /**
     * 海报图 URL。
     */
    private String posterUrl;

    /**
     * 背景图 URL。
     */
    private String backdropUrl;
}
