package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 其他媒体库条目实体（文件级，每个视频文件一行），不刮元数据，缩略图网格展示。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_media_other")
public class MediaOther extends BaseEntity {

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
     * 锚：视频文件的虚拟文件树节点 ID（唯一）。
     */
    private String fileNodeId;

    /**
     * 条目名称（默认取文件名）。
     */
    private String name;

    /**
     * 来源目录ID+相对路径（相对来源目录）+文件名+文件大小的哈希，用于增量扫描 diff，为空视为已变化。
     */
    private String fileHash;

    /**
     * 时长（毫秒），ffprobe 探测。
     */
    private Long durationMs;

    /**
     * 封装格式，ffprobe 探测。
     */
    private String container;

    /**
     * 视频编码，ffprobe 探测。
     */
    private String videoCodec;

    /**
     * 音频编码，ffprobe 探测。
     */
    private String audioCodec;

    /**
     * 视频宽度，ffprobe 探测。
     */
    private Integer width;

    /**
     * 视频高度，ffprobe 探测。
     */
    private Integer height;

    /**
     * 缩略图路径（ffmpeg 截图）。
     */
    private String thumbnailPath;

    /**
     * 播放进度（毫秒）。
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
     * 批次扫描时间（扫描开始时刻），用于批次清理判定。
     */
    private LocalDateTime scanTime;
}
