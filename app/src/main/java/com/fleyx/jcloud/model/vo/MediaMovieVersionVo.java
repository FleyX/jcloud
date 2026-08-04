package com.fleyx.jcloud.model.vo;

import lombok.Data;

/**
 * 电影版本视图（电影详情页版本列表，一行一个视频文件明细）。
 */
@Data
public class MediaMovieVersionVo {

    /**
     * 电影文件明细 ID。
     */
    private String id;

    /**
     * 视频文件节点 ID。
     */
    private String fileNodeId;

    /**
     * 文件名。
     */
    private String fileName;

    /**
     * 文件大小（字节）。
     */
    private Long fileSize;

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
     * 视频宽。
     */
    private Integer width;

    /**
     * 视频高。
     */
    private Integer height;
}
