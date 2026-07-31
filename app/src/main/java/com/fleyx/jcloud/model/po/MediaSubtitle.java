package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 媒体条目外部字幕关联实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_media_subtitle")
public class MediaSubtitle extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属媒体条目 ID。
     */
    private String itemId;

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
