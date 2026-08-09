package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.config.MediaProperties;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaSubtitleMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaSubtitle;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.vo.MediaPlaybackConfigVo;
import com.fleyx.jcloud.model.vo.MediaPlaybackInfoVo;
import com.fleyx.jcloud.service.MediaPlaybackService;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.service.support.MediaPlaybackResolveSupport;
import com.fleyx.jcloud.service.support.MediaPlaybackResolveSupport.Playable;
import com.fleyx.jcloud.service.support.MediaProbeSupport;
import com.fleyx.jcloud.service.support.MediaSubtitleSupport;
import com.fleyx.jcloud.service.support.PlaybackConfigConstants;
import com.fleyx.jcloud.service.support.TranscodeCommandBuilder;
import com.fleyx.jcloud.service.support.TranscodeSession;
import com.fleyx.jcloud.service.support.TranscodeSessionManager;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 媒体播放服务实现（issue #19 起播放链路切换到新模型）。
 * <p>
 * 播放入参 ID 为标题级行 ID：电影 → t_media_movie、集 → t_media_episode、其他 → t_media_other；
 * 播放进度记录到标题级行（一部电影多版本共享），续播通过 last_play_file_id 定位具体版本文件，
 * 直放/实时转码/字幕提取均从文件明细行取文件事实（ffprobe 结果、文件节点）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaPlaybackServiceImpl implements MediaPlaybackService {

    // 直放白名单与直放判定单一定义在 PlaybackConfigConstants（ADR 0024）

    private final MediaPlaybackResolveSupport mediaPlaybackResolveSupport;
    private final FileMapper fileMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final UserMapper userMapper;
    private final RemoteFileService remoteFileService;
    private final MediaProbeSupport mediaProbeSupport;
    private final MediaSubtitleSupport mediaSubtitleSupport;
    private final MediaSubtitleMapper mediaSubtitleMapper;
    private final TranscodeSessionManager transcodeSessionManager;
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
        MediaProbeResult probe = probePlayableFile(node, playable, userId);

        MediaPlaybackInfoVo vo = new MediaPlaybackInfoVo();
        vo.setDurationMs(probe.durationMs() != null ? probe.durationMs() : playable.durationMs());
        vo.setContainer(firstNonNull(probe.container(), playable.container()));
        vo.setVideoCodec(firstNonNull(probe.videoCodec(), playable.videoCodec()));
        vo.setAudioCodec(firstNonNull(probe.audioCodec(), playable.audioCodec()));
        vo.setWidth(firstNonNull(probe.width(), playable.width()));
        vo.setHeight(firstNonNull(probe.height(), playable.height()));
        vo.setAudioTracks(probe.audioTracks());
        vo.setSubtitleTracks(probe.subtitleTracks());
        vo.setSubtitles(mediaSubtitleSupport.buildSubtitleList(probe.subtitleTracks(), playable.fileRowId()));
        vo.setEffectiveBitRate(resolveEffectiveBitRate(probe, playable, vo.getDurationMs()));
        vo.setProgressMs(playable.progressMs());
        // 本次解析使用的文件明细行 ID：前端播放/进度上报以此定位版本（续播定位语义不变）
        vo.setVersionId(playable.fileRowId());

        String versionSuffix = versionId == null ? "" : "?versionId=" + versionId;
        if (PlaybackConfigConstants.canDirectPlay(vo.getContainer(), vo.getVideoCodec(), vo.getAudioCodec())) {
            vo.setMode("direct");
            vo.setDirectUrl("/jcloud/api/media/items/" + id + "/stream" + versionSuffix);
        } else {
            vo.setMode("transcode");
            vo.setTranscodeUrl("/jcloud/api/media/items/" + id + "/transcode" + versionSuffix);
        }
        return vo;
    }

    @Override
    public MediaStreamResult stream(String id, String userId, String rangeHeader, String versionId) {
        Playable playable = mediaPlaybackResolveSupport.resolve(id, userId, versionId);
        FileNode node = requireFileNode(playable.fileNodeId(), userId);
        long total = node.getSize() == null ? 0L : node.getSize();
        String contentType = resolveVideoContentType(node);

        if (FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
            // 远程文件不支持 Range，整段流式返回
            FileDownloadResult result = remoteFileService.download(node, userId);
            return new MediaStreamResult(result, null, null, total, node.getName());
        }

        Path physicalPath = resolveLocalPath(node, userId);
        try {
            long size = Files.size(physicalPath);
            Long start = null;
            Long end = null;
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] parts = rangeHeader.substring(6).split("-");
                start = parts[0].isBlank() ? Math.max(0, size - Long.parseLong(parts[1])) : Long.parseLong(parts[0]);
                end = parts.length > 1 && !parts[1].isBlank() && parts[0].isBlank() ? size - 1
                        : (parts.length > 1 && !parts[1].isBlank() ? Math.min(Long.parseLong(parts[1]), size - 1) : size - 1);
            }
            FileChannel channel = FileChannel.open(physicalPath, StandardOpenOption.READ);
            InputStream in;
            if (start != null) {
                channel.position(start);
                long length = end - start + 1;
                in = boundedStream(Channels.newInputStream(channel), length, channel);
            } else {
                in = Channels.newInputStream(channel);
            }
            FileDownloadResult result = new FileDownloadResult(node.getName(), in, contentType, size);
            return new MediaStreamResult(result, start, end, size, node.getName());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "媒体流读取失败", e);
        }
    }

    @Override
    public Path extractSubtitle(String id, int index, long offsetMs, String userId, String versionId) {
        validateOffset(offsetMs);
        Playable playable = mediaPlaybackResolveSupport.resolve(id, userId, versionId);
        StorageSpace space = systemStorageSpaceProvider.getSystemSpace();
        Path canonical = Path.of(space.getPath(), "system", MediaSubtitleSupport.SUBTITLE_CACHE_DIR,
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
                    inputPath = resolveLocalPath(node, userId).toString();
                }
                Process process = new ProcessBuilder(mediaProperties.getFfmpegPath(), "-y", "-v", "error",
                        "-i", inputPath, "-map", "0:s:" + index, "-f", "webvtt", canonical.toString()).start();
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
        return mediaSubtitleSupport.resolveOffsetVtt(
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
        Path localPath = FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())
                ? null : resolveLocalPath(node, userId);
        Path canonical = mediaSubtitleSupport.resolveExternalVtt(subtitle, node, localPath, userId);
        if (offsetMs == 0) {
            return canonical;
        }
        return mediaSubtitleSupport.resolveOffsetVtt(node, canonical, offsetMs);
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
    public TranscodeSession createTranscodeSession(String id, long startMs,
                                                   Integer audioIndex, Long targetBitrateKbps,
                                                   Integer maxHeight, boolean forceVideoTranscode,
                                                   String userId, String versionId) {
        TranscodeCommandBuilder.validateParams(targetBitrateKbps, maxHeight);
        Playable playable = mediaPlaybackResolveSupport.resolve(id, userId, versionId);
        FileNode node = requireFileNode(playable.fileNodeId(), userId);
        MediaProbeResult probe = probePlayableFile(node, playable, userId);
        String videoCodec = firstNonNull(probe.videoCodec(), playable.videoCodec());
        String audioCodec = resolveSelectedAudioCodec(probe, audioIndex, playable);
        TranscodeCommandBuilder.TranscodeRequest request;
        if (FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
            request = new TranscodeCommandBuilder.TranscodeRequest(startMs, audioIndex, null,
                    () -> remoteFileService.download(node, userId).getInputStream(),
                    videoCodec, audioCodec, targetBitrateKbps, maxHeight, forceVideoTranscode);
        } else {
            request = new TranscodeCommandBuilder.TranscodeRequest(startMs, audioIndex,
                    resolveLocalPath(node, userId), null,
                    videoCodec, audioCodec, targetBitrateKbps, maxHeight, forceVideoTranscode);
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

    /**
     * 按文件扩展名推导视频 MIME，浏览器对 application/octet-stream 的媒体流拒绝播放。
     */
    private String resolveVideoContentType(FileNode node) {
        String name = node.getName() == null ? "" : node.getName().toLowerCase();
        int idx = name.lastIndexOf('.');
        String ext = idx < 0 ? "" : name.substring(idx + 1);
        return switch (ext) {
            case "mp4", "m4v", "mov" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mkv" -> "video/x-matroska";
            case "avi" -> "video/x-msvideo";
            case "ts", "m2ts" -> "video/mp2t";
            case "mpg", "mpeg" -> "video/mpeg";
            case "3gp" -> "video/3gpp";
            default -> node.getMimeType() != null && node.getMimeType().startsWith("video/")
                    ? node.getMimeType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }

    private MediaProbeResult probePlayableFile(FileNode node, Playable playable, String userId) {
        try {
            if (FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
                try (InputStream in = remoteFileService.download(node, userId).getInputStream()) {
                    return mediaProbeSupport.probe(in);
                }
            }
            return mediaProbeSupport.probe(resolveLocalPath(node, userId));
        } catch (Exception e) {
            log.warn("播放探测失败，回退到扫描数据: {}", e.getMessage());
            return new MediaProbeResult(playable.durationMs(), playable.container(), playable.videoCodec(),
                    playable.audioCodec(), playable.width(), playable.height(), null, List.of(), List.of());
        }
    }

    private Path resolveLocalPath(FileNode node, String userId) {
        StorageSpace space = storageSpaceMapper.selectById(node.getStorageSpaceId());
        if (space == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }
        String username = userMapper.selectById(userId).getUsername();
        Map<String, String> nameCache = new HashMap<>();
        if (node.getPath() != null) {
            List<FileNode> ancestors = fileMapper.selectBatchIds(
                    java.util.Arrays.stream(node.getPath().split("\\."))
                            .filter(id -> !FileNodeConstants.ROOT_ID.equals(id)).toList());
            for (FileNode ancestor : ancestors) {
                nameCache.put(ancestor.getId(), ancestor.getName());
            }
        }
        Path path = FilePathUtil.resolvePhysicalPath(node, FilePathUtil.contextOf(space, username, nameCache));
        if (!Files.exists(path)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文件已丢失");
        }
        return path;
    }

    private FileNode requireFileNode(String fileNodeId, String userId) {
        FileNode node = fileMapper.selectById(fileNodeId);
        if (node == null || !userId.equals(node.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文件不存在");
        }
        return node;
    }

    private InputStream boundedStream(InputStream in, long length, FileChannel channel) {
        return new java.io.FilterInputStream(in) {
            private long remaining = length;

            @Override
            public int read() throws java.io.IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int read = super.read();
                if (read > 0) {
                    remaining--;
                }
                return read;
            }

            @Override
            public int read(byte[] b, int off, int len) throws java.io.IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int read = super.read(b, off, (int) Math.min(len, remaining));
                if (read > 0) {
                    remaining -= read;
                }
                return read;
            }

            @Override
            public void close() throws java.io.IOException {
                super.close();
                channel.close();
            }
        };
    }

    private <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }
}
