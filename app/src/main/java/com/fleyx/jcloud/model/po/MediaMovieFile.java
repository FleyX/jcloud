package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 电影文件明细实体，一行一个视频文件（多版本），承载 ffprobe 探测结果与文件变更哈希。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_media_movie_file")
public class MediaMovieFile extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属电影 ID。
     */
    private String movieId;

    /**
     * 锚：视频文件的虚拟文件树节点 ID（唯一）。
     */
    private String fileNodeId;

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
     * 扫描时文件大小（字节），用于重扫 diff。
     */
    private Long fileSize;

    /**
     * 扫描时文件修改时间（毫秒），用于重扫 diff。
     */
    private Long fileLastModified;
}
