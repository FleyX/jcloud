package com.fleyx.jcloud.model.bo;

/**
 * 转码会话创建参数（已收录/纯播放共用）：收敛创建接口与创建核心之间同形重复的参数簇。
 *
 * @param startMs             起始位置（毫秒）
 * @param audioIndex          音轨序号，可为 null
 * @param subtitleIndex       内嵌位图字幕轨序号，可为 null（携带时强制视频转码，烧录进画面；文本轨与非法序号拒绝）
 * @param externalSubtitleId  外挂位图字幕 ID，可为 null（携带时强制视频转码，烧录进画面；语义随链路不同：
 *                            已收录为 t_media_subtitle 记录 ID，纯播放为外挂字幕文件节点 ID）
 * @param targetBitrateKbps   目标视频码率上限 kbps，可为 null（存在时视频强制转码并限码率）
 * @param maxHeight           分辨率高度上限（2160/1080/720/480/360），可为 null
 * @param forceVideoTranscode 前端 MSE 不支持转封装编码时传 true，视频强制转码
 */
public record TranscodeSessionParams(long startMs, Integer audioIndex, Integer subtitleIndex,
                                     String externalSubtitleId, Long targetBitrateKbps, Integer maxHeight,
                                     boolean forceVideoTranscode) {
}
