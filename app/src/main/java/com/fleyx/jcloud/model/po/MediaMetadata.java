package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 媒体元数据实体，按用户隔离，绑定电影条目/剧/季（ADR 0020）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_media_metadata")
public class MediaMetadata extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属用户 ID（元数据按用户隔离）。
     */
    private String userId;

    /**
     * TMDB 条目 ID，local_nfo 来源可为空。
     */
    private Long tmdbId;

    /**
     * 类型：movie / tv / season / episode。
     */
    private String mediaType;

    /**
     * 来源：local_nfo 本地NFO / tmdb。
     */
    private String source;

    /**
     * 完整性：complete 完整 / incomplete 不完整（本地优先缺字段不补）。
     */
    private String completeStatus;

    /**
     * 落盘状态：pending 待落盘 / persisted 已写回视频目录 / failed 落盘失败待重试。
     */
    private String persistStatus;

    /**
     * 海报图文件节点 ID（视频目录下 poster.jpg）。
     */
    private String posterFileNodeId;

    /**
     * 背景图文件节点 ID（视频目录下 fanart.jpg）。
     */
    private String backdropFileNodeId;

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
