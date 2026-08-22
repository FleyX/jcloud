package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 集实体（标题级），以 (season_id, episode_no) 唯一键为身份，不挂匹配状态。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_media_episode")
public class MediaEpisode extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属剧 ID。
     */
    private String seriesId;

    /**
     * 所属季 ID。
     */
    private String seasonId;

    /**
     * 集号。
     */
    private Integer episodeNo;

    /**
     * 集元数据 ID（由剧级匹配派生），未匹配时为空。
     */
    private String metadataId;

    /**
     * 播放进度（毫秒），多版本共享。
     */
    private Long progressMs;

    /**
     * 已观看标记：手动标记或播放进度达看完阈值自动置位，置位时进度清零。
     */
    private Boolean watched;

    /**
     * 最近播放时间。
     */
    private LocalDateTime lastPlayTime;

    /**
     * 最近播放的集文件明细 ID，用于续播定位版本。
     */
    private String lastPlayFileId;
}
