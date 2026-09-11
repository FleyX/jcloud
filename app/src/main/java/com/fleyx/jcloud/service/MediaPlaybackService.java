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
     * 纯播放模式播放信息（未收录文件直放）：以文件节点开播，文件事实全部来自实时探测，
     * 进度恒为 0、versionId 为 null；不可直放时 mode=transcode（转码端点由后续工单补齐）。
     *
     * @param fileNodeId 文件节点 ID
     * @param userId     用户 ID
     * @return 播放信息
     */
    MediaPlaybackInfoVo getPlaybackInfoByFileNode(String fileNodeId, String userId);

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
     * 纯播放模式直放流：按文件节点读取，行为与 {@link #stream} 一致（本地 Range / 远程整段）。
     *
     * @param fileNodeId  文件节点 ID
     * @param rangeHeader Range 请求头，可为空
     * @param userId      用户 ID
     * @return 下载结果（含实际起始位置与总大小）
     */
    MediaStreamResult streamByFileNode(String fileNodeId, String rangeHeader, String userId);

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
     * 纯播放模式提取内嵌字幕轨为 WebVTT 文件：校验文件归属当前用户，提取/缓存与
     * {@link #extractSubtitle} 共用核心（缓存名 fileRowId_index.vtt 中 fileRowId 即 fileNodeId）。
     *
     * @param fileNodeId 视频文件节点 ID
     * @param index      字幕轨序号
     * @param offsetMs   时间偏移（毫秒），0 不偏移，正值按转码会话起点生成相对时间轴，拒绝负值
     * @param userId     用户 ID
     * @return VTT 文件路径
     */
    Path extractSubtitleByFileNode(String fileNodeId, int index, long offsetMs, String userId);

    /**
     * 纯播放模式读取外挂字幕为 WebVTT 文件：视频与字幕文件节点均校验归属当前用户，
     * 且字幕节点必须命中实时探测集合（同目录前缀匹配规则，等价于已收录链路的明细行归属校验）。
     *
     * @param fileNodeId          视频文件节点 ID
     * @param subtitleFileNodeId  外挂字幕文件节点 ID
     * @param offsetMs            时间偏移（毫秒），0 不偏移，正值按转码会话起点生成相对时间轴，拒绝负值
     * @param userId              用户 ID
     * @return VTT 文件路径
     */
    Path extractExternalSubtitleByFileNode(String fileNodeId, String subtitleFileNodeId, long offsetMs,
                                           String userId);

    /**
     * 创建转码会话（按流决策转封装/转码）。
     *
     * @param itemId              条目 ID
     * @param startMs             起始位置（毫秒）
     * @param audioIndex          音轨序号，可为 null
     * @param subtitleIndex       内嵌位图字幕轨序号，可为 null（携带时强制视频转码，烧录进画面；文本轨与非法序号拒绝）
     * @param externalSubtitleId  外挂位图字幕记录 ID，可为 null（携带时强制视频转码，烧录进画面；
     *                            文本外挂、非本明细行归属、与 subtitleIndex 同传均拒绝）
     * @param targetBitrateKbps   目标视频码率上限 kbps，可为 null（存在时视频强制转码并限码率）
     * @param maxHeight           分辨率高度上限（2160/1080/720/480/360），可为 null
     * @param forceVideoTranscode 前端 MSE 不支持转封装编码时传 true，视频强制转码
     * @param userId              用户 ID
     * @param versionId           文件明细行 ID（电影版本 ID），为空按续播定位
     * @return 转码会话
     */
    TranscodeSession createTranscodeSession(String itemId, long startMs, Integer audioIndex, Integer subtitleIndex,
                                            String externalSubtitleId, Long targetBitrateKbps, Integer maxHeight,
                                            boolean forceVideoTranscode, String userId, String versionId);

    /**
     * 纯播放模式创建转码会话（未收录文件）：以文件节点开播，校验文件归属当前用户，
     * 文件事实全部来自实时探测（strict，无存档字段可兜底）；参数语义与 {@link #createTranscodeSession} 一致，
     * externalSubtitleId 为外挂字幕文件节点 ID（须命中实时探测且为位图格式，工单 04 起支持），不支持版本定位。
     *
     * @param fileNodeId          文件节点 ID
     * @param startMs             起始位置（毫秒）
     * @param audioIndex          音轨序号，可为 null
     * @param subtitleIndex       内嵌位图字幕轨序号，可为 null（携带时强制视频转码，烧录进画面；文本轨与非法序号拒绝）
     * @param externalSubtitleId  外挂字幕文件节点 ID，可为 null（携带时强制视频转码，烧录进画面；
     *                            文本外挂、探测未命中、与 subtitleIndex 同传均拒绝）
     * @param targetBitrateKbps   目标视频码率上限 kbps，可为 null（存在时视频强制转码并限码率）
     * @param maxHeight           分辨率高度上限（2160/1080/720/480/360），可为 null
     * @param forceVideoTranscode 前端 MSE 不支持转封装编码时传 true，视频强制转码
     * @param userId              用户 ID
     * @return 转码会话
     */
    TranscodeSession createTranscodeSessionByFileNode(String fileNodeId, long startMs, Integer audioIndex,
                                                      Integer subtitleIndex, String externalSubtitleId,
                                                      Long targetBitrateKbps, Integer maxHeight,
                                                      boolean forceVideoTranscode, String userId);

    /**
     * 媒体流结果。
     */
    record MediaStreamResult(FileDownloadResult downloadResult, Long rangeStart, Long rangeEnd, long totalSize,
                             String fileName) {
    }
}
