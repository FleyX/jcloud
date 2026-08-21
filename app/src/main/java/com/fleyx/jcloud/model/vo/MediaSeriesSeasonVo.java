package com.fleyx.jcloud.model.vo;

import lombok.Data;

/**
 * 电视剧季视图（详情页季卡片）。
 */
@Data
public class MediaSeriesSeasonVo {

    /**
     * 季 ID。
     */
    private String seasonId;

    /**
     * 季号，为空表示未知季（排最后）。
     */
    private Integer seasonNo;

    /**
     * 季海报图 URL。
     */
    private String posterUrl;

    /**
     * 集数。
     */
    private Long episodeCount;

    /**
     * 是否有观看进度（存在 progress_ms > 0 的集）。
     */
    private Boolean hasProgress;

    /**
     * 已观看标记：本季是否全部集已观看（工单 02 三级联动聚合）。
     */
    private Boolean watched;

    /**
     * 当前用户是否已收藏（季卡片悬浮心形展示用）。
     */
    private Boolean favorited = Boolean.FALSE;
}
