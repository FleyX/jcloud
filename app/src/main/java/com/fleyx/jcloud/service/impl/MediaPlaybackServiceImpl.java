package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.config.MediaProperties;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.vo.MediaPlaybackInfoVo;
import com.fleyx.jcloud.service.MediaPlaybackService;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.service.support.MediaProbeSupport;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 媒体播放服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaPlaybackServiceImpl implements MediaPlaybackService {

    private static final Set<String> DIRECT_CONTAINERS = Set.of("mp4", "mov", "m4v", "webm");
    private static final Set<String> DIRECT_VIDEO_CODECS = Set.of("h264", "hevc", "vp8", "vp9", "av1");
    private static final Set<String> DIRECT_AUDIO_CODECS = Set.of("aac", "mp3", "opus", "vorbis", "flac");
    private static final String SUBTITLE_CACHE_DIR = "media/subtitles";

    private final MediaItemMapper mediaItemMapper;
    private final FileMapper fileMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final UserMapper userMapper;
    private final RemoteFileService remoteFileService;
    private final MediaProbeSupport mediaProbeSupport;
    private final TranscodeSessionManager transcodeSessionManager;
    private final MediaProperties mediaProperties;
    private final com.fleyx.jcloud.service.SystemStorageSpaceProvider systemStorageSpaceProvider;

    @Override
    public MediaPlaybackInfoVo getPlaybackInfo(String itemId, String userId) {
        MediaItem item = requireOwnedItem(itemId, userId);
        MediaProbeResult probe = probeItemFile(item, userId);

        MediaPlaybackInfoVo vo = new MediaPlaybackInfoVo();
        vo.setDurationMs(probe.durationMs() != null ? probe.durationMs() : item.getDurationMs());
        vo.setContainer(firstNonNull(probe.container(), item.getContainer()));
        vo.setVideoCodec(firstNonNull(probe.videoCodec(), item.getVideoCodec()));
        vo.setAudioCodec(firstNonNull(probe.audioCodec(), item.getAudioCodec()));
        vo.setWidth(firstNonNull(probe.width(), item.getWidth()));
        vo.setHeight(firstNonNull(probe.height(), item.getHeight()));
        vo.setAudioTracks(probe.audioTracks());
        vo.setSubtitleTracks(probe.subtitleTracks());
        vo.setProgressMs(item.getProgressMs());

        if (canDirectPlay(vo.getContainer(), vo.getVideoCodec(), vo.getAudioCodec())) {
            vo.setMode("direct");
            vo.setDirectUrl("/jcloud/api/media/items/" + itemId + "/stream");
        } else {
            vo.setMode("transcode");
            vo.setTranscodeUrl("/jcloud/api/media/items/" + itemId + "/transcode");
        }
        return vo;
    }

    @Override
    public MediaStreamResult stream(String itemId, String userId, String rangeHeader) {
        MediaItem item = requireOwnedItem(itemId, userId);
        FileNode node = requireFileNode(item);
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
    public Path extractSubtitle(String itemId, int index, String userId) {
        MediaItem item = requireOwnedItem(itemId, userId);
        StorageSpace space = systemStorageSpaceProvider.getSystemSpace();
        Path target = Path.of(space.getPath(), "system", SUBTITLE_CACHE_DIR, itemId + "_" + index + ".vtt");
        if (Files.exists(target)) {
            return target;
        }
        FileNode node = requireFileNode(item);
        Path tempInput = null;
        try {
            Files.createDirectories(target.getParent());
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
                    "-i", inputPath, "-map", "0:s:" + index, "-f", "webvtt", target.toString()).start();
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new SystemException(ResultCode.SYSTEM_ERROR, "字幕提取超时");
            }
            if (process.exitValue() != 0 || !Files.exists(target)) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "字幕提取失败，该字幕轨可能不受支持");
            }
            return target;
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

    @Override
    public TranscodeSessionManager.TranscodeSession createTranscodeSession(String itemId, long startMs,
                                                                           Integer audioIndex, String userId) {
        MediaItem item = requireOwnedItem(itemId, userId);
        FileNode node = requireFileNode(item);
        if (FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
            return transcodeSessionManager.createSession(userId, startMs, audioIndex, null,
                    () -> remoteFileService.download(node, userId).getInputStream());
        }
        return transcodeSessionManager.createSession(userId, startMs, audioIndex, resolveLocalPath(node, userId), null);
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

    private MediaProbeResult probeItemFile(MediaItem item, String userId) {
        FileNode node = requireFileNode(item);
        try {
            if (FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
                try (InputStream in = remoteFileService.download(node, userId).getInputStream()) {
                    return mediaProbeSupport.probe(in);
                }
            }
            return mediaProbeSupport.probe(resolveLocalPath(node, userId));
        } catch (Exception e) {
            log.warn("播放探测失败，回退到扫描数据: {}", e.getMessage());
            return new MediaProbeResult(item.getDurationMs(), item.getContainer(), item.getVideoCodec(),
                    item.getAudioCodec(), item.getWidth(), item.getHeight(), List.of(), List.of());
        }
    }

    private boolean canDirectPlay(String container, String videoCodec, String audioCodec) {
        if (container == null || videoCodec == null) {
            return false;
        }
        String normalizedContainer = container.toLowerCase();
        // mov 容器按 mp4 处理
        if ("mov".equals(normalizedContainer)) {
            normalizedContainer = "mp4";
        }
        return DIRECT_CONTAINERS.contains(normalizedContainer)
                && DIRECT_VIDEO_CODECS.contains(videoCodec.toLowerCase())
                && (audioCodec == null || DIRECT_AUDIO_CODECS.contains(audioCodec.toLowerCase()));
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

    private MediaItem requireOwnedItem(String itemId, String userId) {
        MediaItem item = mediaItemMapper.selectById(itemId);
        if (item == null || !userId.equals(item.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
        }
        return item;
    }

    private FileNode requireFileNode(MediaItem item) {
        FileNode node = fileMapper.selectById(item.getFileNodeId());
        if (node == null || !item.getUserId().equals(node.getUserId())) {
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
