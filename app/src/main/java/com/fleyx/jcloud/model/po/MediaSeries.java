package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 剧集实体（标题级，文件夹锚定，库级归属）。
 * 媒体模型重构 contract 阶段（issue #21）由 t_media_series_v2 rename 而来。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_media_series")
public class MediaSeries extends BaseEntity {

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
     * 锚：剧文件夹的虚拟文件树节点 ID（唯一）。
     */
    private String folderNodeId;

    /**
     * 剧名（默认取文件夹名）。
     */
    private String seriesName;

    /**
     * 剧文件夹名解析出的首播年份，用于 TMDB 匹配消歧。
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
     * 元数据完整性标志（聚合语义：剧自身且所有季、集均完整才为完整）。
     */
    private Boolean metadataComplete;

    /**
     * 已观看标记：剧自身的标记（工单 02 三级联动使用），默认 false。
     */
    private Boolean watched;

    /**
     * 剧内最早一集的文件修改时间（毫秒）。
     */
    private Long minFileLastModified;

    /**
     * 当前剧集文件明细的最新入库时间。
     */
    private LocalDateTime latestAddedTime;

    /**
     * 批次扫描时间（扫描开始时刻），用于批次清理判定。
     */
    private LocalDateTime scanTime;
}
