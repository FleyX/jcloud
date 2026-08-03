package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 媒体条目视图（海报墙卡片）。
 */
@Data
public class MediaItemVo {

    /**
     * 条目 ID。
     */
    private String id;

    /**
     * 关联文件节点 ID。
     */
    private String fileNodeId;

    /**
     * 条目类型：movie / episode / other；剧集行手动修正返回 series。
     */
    private String itemType;

    /**
     * 文件名。
     */
    private String fileName;

    /**
     * 匹配状态。
     */
    private String matchStatus;

    /**
     * 元数据完整性标志（电影/剧集行），未匹配无元数据时为 false。
     */
    private Boolean metadataComplete;

    /**
     * 元数据 ID。
     */
    private String metadataId;

    /**
     * 所属剧 ID，仅 episode 有效。
     */
    private String seriesId;

    /**
     * 所属剧名，仅 episode 有效。
     */
    private String seriesName;

    /**
     * 展示标题（元数据标题或文件名）。
     */
    private String title;

    /**
     * 海报图 URL。
     */
    private String posterUrl;

    /**
     * 上映/首播日期。
     */
    private String releaseDate;

    /**
     * 评分。
     */
    private Double voteAverage;

    /**
     * 时长（毫秒）。
     */
    private Long durationMs;

    /**
     * 季号。
     */
    private Integer seasonNo;

    /**
     * 集号。
     */
    private Integer episodeNo;

    /**
     * 播放进度（毫秒）。
     */
    private Long progressMs;

    /**
     * 最近播放时间。
     */
    private LocalDateTime lastPlayTime;

    /**
     * 当前用户是否已收藏（本票填充：电影/剧集/其他海报墙、季/集列表；影视首页不填充恒为 false）。
     */
    private Boolean favorited = Boolean.FALSE;
}
