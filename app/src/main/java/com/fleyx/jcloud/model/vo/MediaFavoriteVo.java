package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 我的收藏条目视图（收藏页卡片）。
 * <p>
 * 五类实体（电影/剧集/季/集/其他）统一结构；季/集卡片标题由前端用
 * seriesName + seasonNo/episodeNo 组合（后端不做格式化）。
 */
@Data
public class MediaFavoriteVo {

    /**
     * 归属实体类型：movie / series / season / episode / other。
     */
    private String ownerType;

    /**
     * 归属实体 ID。
     */
    private String ownerId;

    /**
     * 收藏状态（本页数据均来自收藏表，恒为 true）。
     */
    private Boolean favorited = Boolean.TRUE;

    /**
     * 收藏时间。
     */
    private LocalDateTime favoriteTime;

    /**
     * 展示标题（元数据标题或条目名；季/集由前端组合标题，可为空）。
     */
    private String title;

    /**
     * 文件名/条目名（other 有效，其余类型为空）。
     */
    private String fileName;

    /**
     * 海报图 URL（other 为空，前端回退文件预览缩略图）。
     */
    private String posterUrl;

    /**
     * 关联文件节点 ID（other 有效，缩略图回退与播放用）。
     */
    private String fileNodeId;

    /**
     * 时长（毫秒，other 有效）。
     */
    private Long durationMs;

    /**
     * 已观看标记：所属五类实体（电影/集/其他由进度驱动或手动置位；剧/季工单 02 填充分级联动）。
     */
    private Boolean watched;

    /**
     * 上映/首播日期（movie/series）。
     */
    private String releaseDate;

    /**
     * 评分（movie/series）。
     */
    private Double voteAverage;

    /**
     * 匹配状态（前端推导未识别角标；季/集沿用所属剧状态）。
     */
    private String matchStatus;

    /**
     * 所属剧 ID（season/episode 导航用）。
     */
    private String seriesId;

    /**
     * 所属剧名（season/episode 标题组合用）。
     */
    private String seriesName;

    /**
     * 季号（season/episode）。
     */
    private Integer seasonNo;

    /**
     * 集号（episode）。
     */
    private Integer episodeNo;
}
