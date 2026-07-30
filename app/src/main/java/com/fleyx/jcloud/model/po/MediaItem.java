package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 媒体条目实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_media_item")
public class MediaItem extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属用户 ID。
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
     * 关联文件节点 ID。
     */
    private String fileNodeId;

    /**
     * 条目类型：movie / episode / other。
     */
    private String itemType;

    /**
     * 匹配到的元数据 ID。
     */
    private String metadataId;

    /**
     * 所属剧名，仅 episode 有效。
     */
    private String seriesName;

    /**
     * 所属剧 ID，仅 episode 有效。
     */
    private String seriesId;

    /**
     * 所属季 ID，仅 episode 有效。
     */
    private String seasonId;

    /**
     * 季号，仅 episode 有效。
     */
    private Integer seasonNo;

    /**
     * 集号，仅 episode 有效。
     */
    private Integer episodeNo;

    /**
     * 匹配状态：matched / manual / unmatched / none。
     */
    private String matchStatus;

    /**
     * 时长（毫秒）。
     */
    private Long durationMs;

    /**
     * 封装格式。
     */
    private String container;

    /**
     * 视频编码。
     */
    private String videoCodec;

    /**
     * 音频编码。
     */
    private String audioCodec;

    /**
     * 视频宽度。
     */
    private Integer width;

    /**
     * 视频高度。
     */
    private Integer height;

    /**
     * 扫描时文件大小（字节）。
     */
    private Long fileSize;

    /**
     * 扫描时文件修改时间（毫秒）。
     */
    private Long fileLastModified;

    /**
     * 来源目录ID+相对路径（相对来源目录）+文件名+文件大小的哈希，用于增量扫描 diff，为空视为已变化。
     */
    private String fileHash;

    /**
     * 播放进度（毫秒）。
     */
    private Long progressMs;

    /**
     * 最近播放时间。
     */
    private LocalDateTime lastPlayTime;
}
