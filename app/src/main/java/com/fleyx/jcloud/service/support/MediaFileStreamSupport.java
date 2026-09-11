package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.service.MediaPlaybackService;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件节点流式读取支撑组件（纯播放直放流与影视模式直放流共用）。
 * <p>
 * 远程来源按现有语义整段流式返回（不支持 Range）；本地文件走 FileChannel，
 * 支持 Range 定界读取；按扩展名推导视频 MIME，避免浏览器拒绝播放。
 */
@Component
@RequiredArgsConstructor
public class MediaFileStreamSupport {

    private final FileMapper fileMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final UserMapper userMapper;
    private final RemoteFileService remoteFileService;

    /**
     * 按文件节点读取直放流（远程整段/本地 Range），调用方需先完成归属校验。
     */
    public MediaPlaybackService.MediaStreamResult streamNode(FileNode node, String rangeHeader, String userId) {
        long total = node.getSize() == null ? 0L : node.getSize();
        String contentType = resolveVideoContentType(node);

        if (FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
            // 远程文件不支持 Range，整段流式返回
            FileDownloadResult result = remoteFileService.download(node, userId);
            return new MediaPlaybackService.MediaStreamResult(result, null, null, total, node.getName());
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
            return new MediaPlaybackService.MediaStreamResult(result, start, end, size, node.getName());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "媒体流读取失败", e);
        }
    }

    /**
     * 解析本地文件物理路径（含存储空间与用户上下文装配），文件不存在抛 NOT_FOUND。
     */
    public Path resolveLocalPath(FileNode node, String userId) {
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
}
