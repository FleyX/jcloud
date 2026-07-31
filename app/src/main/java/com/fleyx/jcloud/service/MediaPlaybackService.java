package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.vo.MediaPlaybackInfoVo;
import com.fleyx.jcloud.service.support.TranscodeSessionManager;

import java.nio.file.Path;

/**
 * 媒体播放服务。
 */
public interface MediaPlaybackService {

    /**
     * 获取播放信息（含直放/转码判定与轨道列表）。
     *
     * @param itemId 条目 ID
     * @param userId 用户 ID
     * @return 播放信息
     */
    MediaPlaybackInfoVo getPlaybackInfo(String itemId, String userId);

    /**
     * 直放流式读取，支持 Range。
     *
     * @param itemId      条目 ID
     * @param userId      用户 ID
     * @param rangeHeader Range 请求头，可为空
     * @return 下载结果（含实际起始位置与总大小）
     */
    MediaStreamResult stream(String itemId, String userId, String rangeHeader);

    /**
     * 提取字幕轨为 WebVTT 文件。
     *
     * @param itemId 条目 ID
     * @param index  字幕轨序号
     * @param userId 用户 ID
     * @return VTT 文件路径
     */
    Path extractSubtitle(String itemId, int index, String userId);

    /**
     * 读取外部字幕为 WebVTT 文件（vtt 原样返回，srt/ass/ssa 经 ffmpeg 转换缓存）。
     *
     * @param itemId     条目 ID
     * @param subtitleId 外部字幕记录 ID
     * @param userId     用户 ID
     * @return VTT 文件路径
     */
    Path extractExternalSubtitle(String itemId, String subtitleId, String userId);

    /**
     * 创建转码会话（按流决策转封装/转码）。
     *
     * @param itemId              条目 ID
     * @param startMs             起始位置（毫秒）
     * @param audioIndex          音轨序号，可为 null
     * @param targetBitrateKbps   目标视频码率上限 kbps，可为 null（存在时视频强制转码并限码率）
     * @param maxHeight           分辨率高度上限（2160/1080/720/480/360），可为 null
     * @param forceVideoTranscode 前端 MSE 不支持转封装编码时传 true，视频强制转码
     * @param userId              用户 ID
     * @return 转码会话
     */
    TranscodeSessionManager.TranscodeSession createTranscodeSession(String itemId, long startMs, Integer audioIndex,
                                                                    Long targetBitrateKbps, Integer maxHeight,
                                                                    boolean forceVideoTranscode, String userId);

    /**
     * 媒体流结果。
     */
    record MediaStreamResult(FileDownloadResult downloadResult, Long rangeStart, Long rangeEnd, long totalSize,
                             String fileName) {
    }
}
