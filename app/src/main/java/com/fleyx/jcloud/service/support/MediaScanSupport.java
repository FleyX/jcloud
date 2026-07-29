package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.MediaFileNameParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 媒体库扫描辅助组件：ffprobe 探测填充、剧名/目录名解析等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaScanSupport {

    private final FileMapper fileMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final RemoteFileService remoteFileService;
    private final MediaProbeSupport mediaProbeSupport;

    /**
     * ffprobe 探测并填充条目的时长/编码/分辨率，失败仅记日志。
     */
    public void fillProbeResult(MediaItem item, FileNode file, String username, Map<String, String> idToName) {
        try {
            MediaProbeResult probe = probeFile(file, username, idToName);
            item.setDurationMs(probe.durationMs());
            item.setContainer(probe.container());
            item.setVideoCodec(probe.videoCodec());
            item.setAudioCodec(probe.audioCodec());
            item.setWidth(probe.width());
            item.setHeight(probe.height());
        } catch (Exception e) {
            log.warn("ffprobe 探测失败: {}, {}", file.getName(), e.getMessage());
        }
    }

    private MediaProbeResult probeFile(FileNode file, String username, Map<String, String> idToName) {
        if (FileNodeConstants.SOURCE_REMOTE.equals(file.getSourceType())) {
            try (InputStream in = remoteFileService.download(file, file.getUserId()).getInputStream()) {
                return mediaProbeSupport.probe(in);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("远程文件读取失败", e);
            }
        }
        StorageSpace space = storageSpaceMapper.selectById(file.getStorageSpaceId());
        Path physicalPath = FilePathUtil.resolvePhysicalPath(file, FilePathUtil.contextOf(space, username, idToName));
        return mediaProbeSupport.probe(physicalPath);
    }

    /**
     * 解析剧名：优先取目录下的一级子文件夹名，其次用文件名解析结果。
     */
    public String resolveSeriesName(FileNode file, String folderFullIdPath,
                                    MediaFileNameParser.ParseResult parsed, Map<String, String> idToName) {
        String path = file.getPath() == null ? "" : file.getPath();
        if (path.startsWith(folderFullIdPath + FileNodeConstants.PATH_SEPARATOR)) {
            String remain = path.substring(folderFullIdPath.length() + 1);
            String topFolderId = remain.split("\\.")[0];
            String topFolderName = idToName.get(topFolderId);
            if (topFolderName != null) {
                String cleaned = MediaFileNameParser.cleanTitle(topFolderName);
                if (!cleaned.isBlank()) {
                    return cleaned;
                }
            }
        }
        return parsed.title().isBlank() ? null : parsed.title();
    }

    /**
     * 解析祖父目录名（父目录不能是视频目录本身）。
     */
    public String findGrandParentDirName(FileNode file, String folderId, Map<String, String> idToName) {
        String path = file.getPath();
        if (path == null) {
            return null;
        }
        String[] ids = path.split("\\.");
        if (ids.length >= 2 && !ids[ids.length - 1].equals(folderId)) {
            return idToName.get(ids[ids.length - 1]);
        }
        return null;
    }

    /**
     * 补充视频目录祖先节点的名称缓存。
     */
    public void fillAncestorNames(FileNode folder, String userId, Map<String, String> idToName) {
        Set<String> ancestorIds = new java.util.HashSet<>();
        if (folder.getPath() != null) {
            for (String id : folder.getPath().split("\\.")) {
                if (!FileNodeConstants.ROOT_ID.equals(id)) {
                    ancestorIds.add(id);
                }
            }
        }
        ancestorIds.removeAll(idToName.keySet());
        if (!ancestorIds.isEmpty()) {
            for (FileNode ancestor : fileMapper.selectBatchIds(ancestorIds)) {
                if (userId.equals(ancestor.getUserId())) {
                    idToName.put(ancestor.getId(), ancestor.getName());
                }
            }
        }
    }

    /**
     * 判断文件与已扫描条目相比是否未变化（size + mtime）。
     */
    public boolean unchanged(MediaItem item, FileNode file) {
        return Objects.equals(item.getFileSize(), file.getSize())
                && Objects.equals(item.getFileLastModified(), file.getLastModified());
    }
}
