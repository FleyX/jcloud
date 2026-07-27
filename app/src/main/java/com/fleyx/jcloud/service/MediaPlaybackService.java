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
     * 创建转码会话。
     *
     * @param itemId     条目 ID
     * @param startMs    起始位置（毫秒）
     * @param audioIndex 音轨序号，可为 null
     * @param userId     用户 ID
     * @return 转码会话
     */
    TranscodeSessionManager.TranscodeSession createTranscodeSession(String itemId, long startMs, Integer audioIndex, String userId);

    /**
     * 媒体流结果。
     */
    record MediaStreamResult(FileDownloadResult downloadResult, Long rangeStart, Long rangeEnd, long totalSize,
                             String fileName) {
    }
}
