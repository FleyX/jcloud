package com.fleyx.jcloud.model.vo;

import lombok.Data;

/**
 * 播放信息中的统一字幕项视图（内嵌字幕轨与外部字幕统一渲染选择列表）。
 */
@Data
public class MediaSubtitleItemVo {

    /**
     * 字幕类型：embedded 内嵌轨 / external 外部字幕文件。
     */
    private String type;

    /**
     * 内嵌字幕轨序号（type=embedded 有效，external 为 null），
     * 对应 GET /media/items/{id}/subtitles/{index}。
     */
    private Integer index;

    /**
     * 外部字幕 ID（type=external 有效，embedded 为 null）：
     * 已收录播放为 t_media_subtitle 记录 ID（对应 GET /media/items/{id}/subtitles/external/{subtitleId}），
     * 纯播放为外挂字幕文件节点 ID（对应 GET /media/files/{fileNodeId}/subtitles/external/{subtitleFileNodeId}）。
     */
    private String subtitleId;

    /**
     * 展示名：内嵌由 language/title 拼装，外部取文件名后缀解析的标签。
     */
    private String label;

    /**
     * 语言，未知为 null。
     */
    private String language;

    /**
     * 是否默认字幕：内嵌取 ffprobe default 标记，外部取文件名 .default 后缀。
     */
    private Boolean defaulted;

    /**
     * 是否位图字幕（内嵌 PGS/DVD/DVB 等，选中后走转码烧录）；文本轨与外部字幕为 false。
     */
    private Boolean bitmap;
}
