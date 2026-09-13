package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.config.MediaProperties;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaSubtitleMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.bo.TranscodeSessionParams;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaSubtitle;
import com.fleyx.jcloud.model.vo.MediaPlaybackConfigVo;
import com.fleyx.jcloud.model.vo.MediaPlaybackInfoVo;
import com.fleyx.jcloud.service.MediaPlaybackService;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.service.support.MediaBurnInSubtitleSupport;
import com.fleyx.jcloud.service.support.MediaFileStreamSupport;
import com.fleyx.jcloud.service.support.MediaPlaybackResolveSupport;
import com.fleyx.jcloud.service.support.MediaPlaybackResolveSupport.Playable;
import com.fleyx.jcloud.service.support.MediaProbeSupport;
import com.fleyx.jcloud.service.support.MediaSubtitleConvertSupport;
import com.fleyx.jcloud.service.support.MediaSubtitleSupport;
import com.fleyx.jcloud.service.support.PlaybackConfigConstants;
import com.fleyx.jcloud.service.support.TranscodeCommandBuilder;
import com.fleyx.jcloud.service.support.TranscodeSession;
import com.fleyx.jcloud.service.support.TranscodeSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 媒体播放服务实现（issue #19 起播放链路切换到新模型）：播放入参 ID 为标题级行 ID
 * （电影/集/其他），播放进度记录到标题级行，续播通过 last_play_file_id 定位具体版本文件；
 * 纯播放模式（by-file-node 变体）以文件节点开播，文件事实全部来自实时探测，进度恒为 0。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaPlaybackServiceImpl implements MediaPlaybackService {

    // 直放白名单与直放判定单一定义在 PlaybackConfigConstants（ADR 0024）

    private final MediaPlaybackResolveSupport mediaPlaybackResolveSupport;
    private final FileMapper fileMapper;
    private final RemoteFileService remoteFileService;
    private final MediaProbeSupport mediaProbeSupport;
    private final MediaSubtitleSupport mediaSubtitleSupport;
    private final MediaSubtitleConvertSupport mediaSubtitleConvertSupport;
    private final MediaSubtitleMapper mediaSubtitleMapper;
    private final MediaBurnInSubtitleSupport mediaBurnInSubtitleSupport;
    private final TranscodeSessionManager transcodeSessionManager;
    private final MediaFileStreamSupport mediaFileStreamSupport;
    private final MediaProperties mediaProperties;
    private final com.fleyx.jcloud.service.SystemStorageSpaceProvider systemStorageSpaceProvider;

    @Override
    public MediaPlaybackConfigVo getPlaybackConfig() {
        return PlaybackConfigConstants.buildConfig();
    }

    @Override
    public MediaPlaybackInfoVo getPlaybackInfo(String id, String userId, String versionId) {
        Playable playable = mediaPlaybackResolveSupport.resolve(id, userId, versionId);
        FileNode node = requireFileNode(playable.fileNodeId(), userId);
        String versionSuffix = versionId == null ? "" : "?versionId=" + versionId;
        return buildPlaybackInfo(playable, node, userId, false,
                "/jcloud/api/media/items/" + id + "/stream" + versionSuffix,
                "/jcloud/api/media/items/" + id + "/transcode" + versionSuffix);
    }

    @Override
    public MediaPlaybackInfoVo getPlaybackInfoByFileNode(String fileNodeId, String userId) {
        FileNode node = requireFileNode(fileNodeId, userId);
        Playable playable = resolvePurePlayable(node);
        // 纯播放无存档字段兜底，探测失败直接报错（strict）；不可直放时转码端点为 files 形态
        return buildPlaybackInfo(playable, node, userId, true,
                "/jcloud/api/media/files/" + fileNodeId + "/stream",
                "/jcloud/api/media/files/" + fileNodeId + "/transcode");
    }

    @Override
    public MediaStreamResult stream(String id, String userId, String rangeHeader, String versionId) {
        Playable playable = mediaPlaybackResolveSupport.resolve(id, userId, versionId);
        FileNode node = requireFileNode(playable.fileNodeId(), userId);
        return mediaFileStreamSupport.streamNode(node, rangeHeader, userId);
    }

    @Override
    public MediaStreamResult streamByFileNode(String fileNodeId, String rangeHeader, String userId) {
        FileNode node = requireFileNode(fileNodeId, userId);
        return mediaFileStreamSupport.streamNode(node, rangeHeader, userId);
    }

    /**
     * 播放信息装配核心（影视/纯播放共用）：文件事实取 firstNonNull(probe, playable)，
     * strict 时探测失败抛 SystemException（纯播放无存档字段可兜底），否则回退 playable 存档字段。
     */
    private MediaPlaybackInfoVo buildPlaybackInfo(Playable playable, FileNode node, String userId, boolean strict,
                                                  String directUrl, String transcodeUrl) {
        MediaProbeResult probe = strict
                ? probePlayableFileStrict(node, userId)
                : probePlayableFile(node, playable, userId);
        MediaPlaybackInfoVo vo = new MediaPlaybackInfoVo();
        vo.setFileName(node.getName());
        vo.setDurationMs(firstNonNull(probe.durationMs(), playable.durationMs()));
        vo.setContainer(firstNonNull(probe.container(), playable.container()));
        vo.setVideoCodec(firstNonNull(probe.videoCodec(), playable.videoCodec()));
        vo.setAudioCodec(firstNonNull(probe.audioCodec(), playable.audioCodec()));
        vo.setWidth(firstNonNull(probe.width(), playable.width()));
        vo.setHeight(firstNonNull(probe.height(), playable.height()));
        vo.setAudioTracks(probe.audioTracks());
        vo.setSubtitleTracks(probe.subtitleTracks());
        // 纯播放外挂字幕走实时探测（不读扫描关联表）；已收录按明细行关联
        vo.setSubtitles(strict
                ? mediaSubtitleSupport.buildPureSubtitleList(probe.subtitleTracks(), node)
                : mediaSubtitleSupport.buildSubtitleList(probe.subtitleTracks(), playable.fileRowId()));
        vo.setEffectiveBitRate(resolveEffectiveBitRate(probe, playable, vo.getDurationMs()));
        vo.setProgressMs(playable.progressMs());
        // 本次解析使用的文件明细行 ID：前端播放/进度上报以此定位版本（续播定位语义不变）；纯播放为 null
        vo.setVersionId(strict ? null : playable.fileRowId());
        if (PlaybackConfigConstants.canDirectPlay(vo.getContainer(), vo.getVideoCodec(), vo.getAudioCodec())) {
            vo.setMode("direct");
            vo.setDirectUrl(directUrl);
        } else {
            vo.setMode("transcode");
            vo.setTranscodeUrl(transcodeUrl);
        }
        return vo;
    }

    /** 纯播放可播放事实：文件事实字段全部为空（以实时探测为准），进度恒为 0。 */
    private Playable resolvePurePlayable(FileNode node) {
        return new Playable(node.getId(), node.getId(), node.getSize(),
                null, null, null, null, null, null, 0L);
    }

    @Override
    public Path extractSubtitle(String id, int index, long offsetMs, String userId, String versionId) {
        validateOffset(offsetMs);
        Playable playable = mediaPlaybackResolveSupport.resolve(id, userId, versionId);
        return extractSubtitleCore(playable, index, offsetMs, userId);
    }

    @Override
    public Path extractSubtitleByFileNode(String fileNodeId, int index, long offsetMs, String userId) {
        validateOffset(offsetMs);
        Playable playable = resolvePurePlayable(requireFileNode(fileNodeId, userId));
        return extractSubtitleCore(playable, index, offsetMs, userId);
    }

    /**
     * 内嵌字幕提取核心（影视/纯播放共用）：缓存 fileRowId_index.vtt 命中直接返回，
     * 否则 ffmpeg 提取（远程先落地临时文件）；offsetMs>0 时生成独立偏移结果。
     */
    private Path extractSubtitleCore(Playable playable, int index, long offsetMs, String userId) {
        Path canonical = Path.of(systemStorageSpaceProvider.getSystemSpace().getPath(),
                "system", MediaSubtitleConvertSupport.SUBTITLE_CACHE_DIR,
                playable.fileRowId() + "_" + index + ".vtt");
        if (!Files.exists(canonical)) {
            FileNode node = requireFileNode(playable.fileNodeId(), userId);
            Path tempInput = null;
            try {
                Files.createDirectories(canonical.getParent());
                String inputPath;
                if (FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
                    // 远程文件先落地临时文件再提取，保证 ffmpeg 可随机访问
                    tempInput = Files.createTempFile("jcloud-sub-", ".bin");
                    try (InputStream in = remoteFileService.download(node, userId).getInputStream()) {
                        Files.copy(in, tempInput, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    inputPath = tempInput.toString();
                } else {
                    inputPath = mediaFileStreamSupport.resolveLocalPath(node, userId).toString();
                }
                List<String> command = List.of(mediaProperties.getFfmpegPath(), "-y", "-v", "error",
                        "-i", inputPath, "-map", "0:s:" + index, "-f", "webvtt", canonical.toString());
                log.info("提取内嵌字幕轨: {}", String.join(" ", command));
                Process process = new ProcessBuilder(command).start();
                boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new SystemException(ResultCode.SYSTEM_ERROR, "字幕提取超时");
                }
                if (process.exitValue() != 0 || !Files.exists(canonical)) {
                    throw new BusinessException(ResultCode.BUSINESS_ERROR, "字幕提取失败，该字幕轨可能不受支持");
                }
            } catch (BusinessException | SystemException e) {
                throw e;
            } catch (Exception e) {
                throw new SystemException(ResultCode.SYSTEM_ERROR, "字幕提取异常", e);
            } finally {
                if (tempInput != null) {
                    try {
                        Files.deleteIfExists(tempInput);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (offsetMs == 0) {
            return canonical;
        }
        return mediaSubtitleConvertSupport.resolveOffsetVtt(
                canonical, playable.fileRowId() + "_" + index, offsetMs);
    }

    @Override
    public Path extractExternalSubtitle(String id, String subtitleId, long offsetMs, String userId, String versionId) {
        validateOffset(offsetMs);
        Playable playable = mediaPlaybackResolveSupport.resolve(id, userId, versionId);
        MediaSubtitle subtitle = mediaSubtitleMapper.selectById(subtitleId);
        if (subtitle == null || !playable.fileRowId().equals(subtitle.getFileId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "字幕不存在");
        }
        FileNode node = fileMapper.selectById(subtitle.getFileNodeId());
        if (node == null || !userId.equals(node.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "字幕文件不存在");
        }
        return finishExternalSubtitleExtract(subtitle, node, offsetMs, userId);
    }

    @Override
    public Path extractExternalSubtitleByFileNode(String fileNodeId, String subtitleFileNodeId,
                                                  long offsetMs, String userId) {
        validateOffset(offsetMs);
        FileNode video = requireFileNode(fileNodeId, userId);
        FileNode node = requireFileNode(subtitleFileNodeId, userId);
        // 归属校验等价于已收录链路的「记录归属当前明细行」：必须在实时探测命中集合内，否则 404
        MediaSubtitle subtitle = mediaSubtitleSupport.findDetectedSubtitle(video, subtitleFileNodeId);
        return finishExternalSubtitleExtract(subtitle, node, offsetMs, userId);
    }

    /**
     * 外挂字幕提取收尾（已收录/纯播放共用）：本地字幕装物理路径、远程字幕装 null，
     * 经 convertSupport 转/取规范 VTT；offsetMs>0 时生成独立偏移结果。
     */
    private Path finishExternalSubtitleExtract(MediaSubtitle subtitle, FileNode node, long offsetMs, String userId) {
        Path localPath = FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())
                ? null : mediaFileStreamSupport.resolveLocalPath(node, userId);
        Path canonical = mediaSubtitleConvertSupport.resolveExternalVtt(subtitle, node, localPath, userId);
        if (offsetMs == 0) {
            return canonical;
        }
        return mediaSubtitleConvertSupport.resolveOffsetVtt(node, canonical, offsetMs);
    }

    private void validateOffset(long offsetMs) {
        if (offsetMs < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "字幕偏移不能为负");
        }
    }

    /**
     * 计算实际码率：优先 ffprobe bit_rate，缺失时按文件大小与时长估算，均缺失为 null。
     */
    private Long resolveEffectiveBitRate(MediaProbeResult probe, Playable playable, Long durationMs) {
        if (probe.bitRate() != null) {
            return probe.bitRate();
        }
        Long size = playable.fileSize();
        if (size == null || durationMs == null || durationMs <= 0) {
            return null;
        }
        return size * 8 * 1000 / durationMs;
    }

    @Override
    public TranscodeSession createTranscodeSession(String id, String userId, String versionId,
                                                   TranscodeSessionParams transcodeParams) {
        validateTranscodeParams(transcodeParams);
        Playable playable = mediaPlaybackResolveSupport.resolve(id, userId, versionId);
        FileNode node = requireFileNode(playable.fileNodeId(), userId);
        MediaProbeResult probe = probePlayableFile(node, playable, userId);
        // 已收录链路：外挂字幕按 t_media_subtitle 记录 ID 装配
        var external = mediaBurnInSubtitleSupport.resolveExternalSubtitleBurn(
                playable, transcodeParams.externalSubtitleId(), userId,
                subNode -> mediaFileStreamSupport.resolveLocalPath(subNode, userId));
        return createTranscodeSessionCore(playable, node, probe, external, transcodeParams, userId);
    }

    @Override
    public TranscodeSession createTranscodeSessionByFileNode(String fileNodeId, String userId,
                                                             TranscodeSessionParams transcodeParams) {
        validateTranscodeParams(transcodeParams);
        FileNode node = requireFileNode(fileNodeId, userId);
        Playable playable = resolvePurePlayable(node);
        // strict 探测：纯播放无存档字段兜底
        MediaProbeResult probe = probePlayableFileStrict(node, userId);
        // 纯播放链路：外挂字幕按字幕文件节点 ID 实时探测装配（不读关联表）
        var external = mediaBurnInSubtitleSupport.resolveExternalSubtitleBurnByFileNode(
                node, transcodeParams.externalSubtitleId(), userId,
                subNode -> mediaFileStreamSupport.resolveLocalPath(subNode, userId));
        return createTranscodeSessionCore(playable, node, probe, external, transcodeParams, userId);
    }

    /**
     * 转码会话创建前的公共参数校验：码率/分辨率档位合法，内嵌与外挂字幕二选一。
     */
    private void validateTranscodeParams(TranscodeSessionParams transcodeParams) {
        TranscodeCommandBuilder.validateParams(transcodeParams.targetBitrateKbps(), transcodeParams.maxHeight());
        if (transcodeParams.subtitleIndex() != null && transcodeParams.externalSubtitleId() != null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "内嵌与外部字幕只能二选一");
        }
    }

    /**
     * 转码会话创建核心（影视/纯播放共用）：外挂烧录由调用方按模式装配（null 时为 empty）、
     * 校验内嵌位图轨序号、编码取 firstNonNull(probe, playable) 兜底（纯播放 playable 全 null 天然只取 probe）、
     * 本地装物理路径/远程装下载流后创建会话。
     */
    private TranscodeSession createTranscodeSessionCore(Playable playable, FileNode node, MediaProbeResult probe,
                                                        MediaBurnInSubtitleSupport.ExternalSubtitleBurn external,
                                                        TranscodeSessionParams transcodeParams, String userId) {
        mediaBurnInSubtitleSupport.validateSubtitleIndex(probe, transcodeParams.subtitleIndex());
        String videoCodec = firstNonNull(probe.videoCodec(), playable.videoCodec());
        String audioCodec = resolveSelectedAudioCodec(probe, transcodeParams.audioIndex(), playable);
        TranscodeCommandBuilder.TranscodeRequest request;
        if (FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
            request = new TranscodeCommandBuilder.TranscodeRequest(transcodeParams.startMs(),
                    transcodeParams.audioIndex(), null,
                    () -> remoteFileService.download(node, userId).getInputStream(),
                    videoCodec, audioCodec, transcodeParams.targetBitrateKbps(), transcodeParams.maxHeight(),
                    transcodeParams.forceVideoTranscode(), transcodeParams.subtitleIndex(),
                    external.path(), external.stream());
        } else {
            request = new TranscodeCommandBuilder.TranscodeRequest(transcodeParams.startMs(),
                    transcodeParams.audioIndex(),
                    mediaFileStreamSupport.resolveLocalPath(node, userId), null,
                    videoCodec, audioCodec, transcodeParams.targetBitrateKbps(), transcodeParams.maxHeight(),
                    transcodeParams.forceVideoTranscode(), transcodeParams.subtitleIndex(),
                    external.path(), external.stream());
        }
        return transcodeSessionManager.createSession(userId, request);
    }

    /**
     * 解析所选音轨的编码：指定音轨取 probe 对应轨，缺省取首个音轨，probe 缺失回退明细行事实。
     */
    private String resolveSelectedAudioCodec(MediaProbeResult probe, Integer audioIndex, Playable playable) {
        if (audioIndex != null) {
            for (MediaProbeResult.Track track : probe.audioTracks()) {
                if (track.index() == audioIndex) {
                    return track.codec();
                }
            }
        }
        return firstNonNull(probe.audioCodec(), playable.audioCodec());
    }

    private MediaProbeResult probePlayableFile(FileNode node, Playable playable, String userId) {
        try {
            return probeFile(node, userId);
        } catch (Exception e) {
            log.warn("播放探测失败，回退到扫描数据: {}", e.getMessage());
            return new MediaProbeResult(playable.durationMs(), playable.container(), playable.videoCodec(),
                    playable.audioCodec(), playable.width(), playable.height(), null, List.of(), List.of());
        }
    }

    /**
     * 纯播放专用探测：无存档字段可兜底，失败抛 SystemException（带原异常）。
     */
    private MediaProbeResult probePlayableFileStrict(FileNode node, String userId) {
        try {
            return probeFile(node, userId);
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "视频文件探测失败", e);
        }
    }

    private MediaProbeResult probeFile(FileNode node, String userId) throws Exception {
        if (FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
            try (InputStream in = remoteFileService.download(node, userId).getInputStream()) {
                return mediaProbeSupport.probe(in);
            }
        }
        return mediaProbeSupport.probe(mediaFileStreamSupport.resolveLocalPath(node, userId));
    }

    private FileNode requireFileNode(String fileNodeId, String userId) {
        FileNode node = fileMapper.selectById(fileNodeId);
        if (node == null || !userId.equals(node.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文件不存在");
        }
        return node;
    }

    private <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }
}
