package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 电影实体（标题级，文件夹锚定），同一电影文件夹下多个视频聚合为多版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_media_movie")
public class MediaMovie extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属用户 ID（冗余存储，便于按用户清理）。
     */
    private String userId;

    /**
     * 所属媒体库 ID。
     */
    private String directoryId;

    /**
     * 所属来源目录 ID。
     */
    private String sourceId;

    /**
     * 锚：电影文件夹的虚拟文件树节点 ID（唯一）。
     */
    private String folderNodeId;

    /**
     * 电影标题（默认取文件夹名）。
     */
    private String title;

    /**
     * 文件夹名解析出的发行年份，用于 TMDB 匹配消歧。
     */
    private Integer releaseYear;

    /**
     * 匹配到的元数据 ID，未匹配时为空。
     */
    private String metadataId;

    /**
     * 匹配状态：matched / manual / unmatched。
     */
    private String matchStatus;

    /**
     * 元数据完整性标志：必备字段（标题/简介/海报/发行日期/评分）是否齐全。
     */
    private Boolean metadataComplete;

    /**
     * 播放进度（毫秒），多版本共享。
     */
    private Long progressMs;

    /**
     * 最近播放时间。
     */
    private LocalDateTime lastPlayTime;

    /**
     * 最近播放的电影文件明细 ID，用于续播定位版本。
     */
    private String lastPlayFileId;

    /**
     * 批次扫描时间（扫描开始时刻），用于批次清理判定。
     */
    private LocalDateTime scanTime;
}
