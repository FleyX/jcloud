package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 文件明细行外部字幕关联实体（issue #19：关联对象从旧媒体条目改到文件明细行）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_media_subtitle")
public class MediaSubtitle extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属文件明细行 ID：电影/集为 t_media_movie_file / t_media_episode_file 明细行 ID，
     * 其他库直接为 t_media_other 行 ID（other 无明细表，行本身即文件级实体）。
     */
    private String fileId;

    /**
     * 字幕文件对应的文件节点 ID。
     */
    private String fileNodeId;

    /**
     * 字幕格式：srt / ass / ssa / vtt。
     */
    private String format;

    /**
     * 展示标签，从文件名后缀解析。
     */
    private String label;

    /**
     * 是否默认字幕（文件名含 .default 后缀）。
     */
    private Boolean isDefault;
}
