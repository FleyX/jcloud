package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.vo.MediaPlaybackConfigVo;
import com.fleyx.jcloud.model.vo.MediaPlaybackInfoVo;
import com.fleyx.jcloud.service.support.TranscodeSession;
import com.fleyx.jcloud.service.support.TranscodeSessionManager;

import java.nio.file.Path;

/**
 * 媒体播放服务。
 */
public interface MediaPlaybackService {

    /**
     * 获取全局播放配置（ADR 0024）：码率档位阶梯、直放/转封装白名单、看完阈值。
     * 后端常量为唯一事实源，前端进入播放流程时拉取一次并缓存。
     *
     * @return 全局播放配置
     */
    MediaPlaybackConfigVo getPlaybackConfig();

    /**
     * 获取播放信息（含直放/转码判定与轨道列表）。
     *
     * @param itemId    条目 ID
     * @param userId    用户 ID
     * @param versionId 文件明细行 ID（电影版本 ID，issue #21），为空按续播定位（last_play_file_id 优先）
     * @return 播放信息
     */
    MediaPlaybackInfoVo getPlaybackInfo(String itemId, String userId, String versionId);

    /**
     * 直放流式读取，支持 Range。
     *
     * @param itemId      条目 ID
     * @param userId      用户 ID
     * @param rangeHeader Range 请求头，可为空
     * @param versionId   文件明细行 ID（电影版本 ID），为空按续播定位
     * @return 下载结果（含实际起始位置与总大小）
     */
    MediaStreamResult stream(String itemId, String userId, String rangeHeader, String versionId);

    /**
     * 提取字幕轨为 WebVTT 文件。
     *
     * @param itemId    条目 ID
     * @param index     字幕轨序号
     * @param offsetMs  时间偏移（毫秒），0 不偏移（直放原片时间轴），正值按转码会话起点生成相对时间轴，拒绝负值
     * @param userId    用户 ID
     * @param versionId 文件明细行 ID（电影版本 ID），为空按续播定位
     * @return VTT 文件路径
     */
    Path extractSubtitle(String itemId, int index, long offsetMs, String userId, String versionId);

    /**
     * 读取外部字幕为 WebVTT 文件（vtt 原样返回，srt/ass/ssa 经 ffmpeg 转换缓存）。
     *
     * @param itemId     条目 ID
     * @param subtitleId 外部字幕记录 ID
     * @param offsetMs   时间偏移（毫秒），0 不偏移，正值按转码会话起点生成相对时间轴，拒绝负值
     * @param userId     用户 ID
     * @param versionId  文件明细行 ID（电影版本 ID），为空按续播定位
     * @return VTT 文件路径
     */
    Path extractExternalSubtitle(String itemId, String subtitleId, long offsetMs, String userId, String versionId);

    /**
     * 创建转码会话（按流决策转封装/转码）。
     *
     * @param itemId              条目 ID
     * @param startMs             起始位置（毫秒）
     * @param audioIndex          音轨序号，可为 null
     * @param subtitleIndex       内嵌位图字幕轨序号，可为 null（携带时强制视频转码，烧录进画面；文本轨与非法序号拒绝）
     * @param targetBitrateKbps   目标视频码率上限 kbps，可为 null（存在时视频强制转码并限码率）
     * @param maxHeight           分辨率高度上限（2160/1080/720/480/360），可为 null
     * @param forceVideoTranscode 前端 MSE 不支持转封装编码时传 true，视频强制转码
     * @param userId              用户 ID
     * @param versionId           文件明细行 ID（电影版本 ID），为空按续播定位
     * @return 转码会话
     */
    TranscodeSession createTranscodeSession(String itemId, long startMs, Integer audioIndex, Integer subtitleIndex,
                                            Long targetBitrateKbps, Integer maxHeight,
                                            boolean forceVideoTranscode, String userId, String versionId);

    /**
     * 媒体流结果。
     */
    record MediaStreamResult(FileDownloadResult downloadResult, Long rangeStart, Long rangeEnd, long totalSize,
                             String fileName) {
    }
}
