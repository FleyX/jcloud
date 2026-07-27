package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaType;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.service.MediaScanService;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.MediaProbeSupport;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.MediaFileNameParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 媒体库扫描服务实现。
 */
@Slf4j
@Service
public class MediaScanServiceImpl implements MediaScanService {

    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final MediaItemMapper mediaItemMapper;
    private final FileMapper fileMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final UserMapper userMapper;
    private final RemoteFileService remoteFileService;
    private final MediaProbeSupport mediaProbeSupport;
    private final TmdbService tmdbService;
    private final TaskExecutor taskExecutor;

    public MediaScanServiceImpl(MediaDirectoryMapper mediaDirectoryMapper, MediaItemMapper mediaItemMapper,
                                FileMapper fileMapper, StorageSpaceMapper storageSpaceMapper, UserMapper userMapper,
                                RemoteFileService remoteFileService, MediaProbeSupport mediaProbeSupport,
                                TmdbService tmdbService, @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.mediaDirectoryMapper = mediaDirectoryMapper;
        this.mediaItemMapper = mediaItemMapper;
        this.fileMapper = fileMapper;
        this.storageSpaceMapper = storageSpaceMapper;
        this.userMapper = userMapper;
        this.remoteFileService = remoteFileService;
        this.mediaProbeSupport = mediaProbeSupport;
        this.tmdbService = tmdbService;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 正在扫描的目录 ID 集合，防止并发重复扫描。
     */
    private final Set<String> runningScans = ConcurrentHashMap.newKeySet();

    @Override
    public void submitScan(String directoryId, String userId) {
        taskExecutor.execute(() -> scan(directoryId));
    }

    @Override
    public void scan(String directoryId) {
        if (!runningScans.add(directoryId)) {
            log.info("目录正在扫描中，跳过: {}", directoryId);
            return;
        }
        MediaDirectory directory = mediaDirectoryMapper.selectById(directoryId);
        try {
            if (directory == null) {
                return;
            }
            boolean partial = doScan(directory);
            updateScanResult(directory, partial ? "PARTIAL" : "COMPLETED", partial ? "部分文件扫描失败" : null);
        } catch (Exception e) {
            log.error("媒体目录扫描失败: {}", directoryId, e);
            if (directory != null) {
                updateScanResult(directory, "FAILED", e.getMessage());
            }
        } finally {
            runningScans.remove(directoryId);
        }
    }

    private boolean doScan(MediaDirectory directory) {
        String userId = directory.getUserId();
        FileNode folder = fileMapper.selectById(directory.getFileNodeId());
        if (folder == null || !userId.equals(folder.getUserId()) || !"folder".equals(folder.getType())) {
            throw new IllegalStateException("视频目录对应的文件夹不存在");
        }
        String username = userMapper.selectById(userId).getUsername();

        List<FileNode> nodes = fileMapper.selectByIdPathPrefix(userId, folder.getPath(), folder.getId());
        Map<String, String> idToName = FilePathUtil.buildNameCache(nodes);
        fillAncestorNames(folder, userId, idToName);
        String folderFullIdPath = FilePathUtil.fullIdPath(folder);

        List<FileNode> videoFiles = new ArrayList<>();
        for (FileNode node : nodes) {
            if ("file".equals(node.getType()) && MediaFileNameParser.isVideoFile(node.getName())) {
                videoFiles.add(node);
            }
        }

        Map<String, MediaItem> existingMap = new HashMap<>();
        for (MediaItem item : mediaItemMapper.selectList(
                new LambdaQueryWrapper<MediaItem>().eq(MediaItem::getDirectoryId, directory.getId()))) {
            existingMap.put(item.getFileNodeId(), item);
        }

        MediaType mediaType = MediaType.of(directory.getMediaType());
        Map<String, String> seriesMatchCache = new HashMap<>();
        boolean partial = false;
        Set<String> seenFileNodeIds = new HashSet<>();
        for (FileNode file : videoFiles) {
            seenFileNodeIds.add(file.getId());
            MediaItem existing = existingMap.get(file.getId());
            if (existing != null && unchanged(existing, file)) {
                continue;
            }
            try {
                scanFile(directory, folder.getId(), folderFullIdPath, file, existing, mediaType, idToName, username, seriesMatchCache);
            } catch (Exception e) {
                log.warn("扫描文件失败: {}, {}", file.getName(), e.getMessage());
                partial = true;
            }
        }
        // 清理已消失文件的条目
        for (MediaItem item : existingMap.values()) {
            if (!seenFileNodeIds.contains(item.getFileNodeId())) {
                mediaItemMapper.deleteById(item.getId());
            }
        }
        return partial;
    }

    private void scanFile(MediaDirectory directory, String folderId, String folderFullIdPath, FileNode file,
                          MediaItem existing, MediaType mediaType, Map<String, String> idToName,
                          String username, Map<String, String> seriesMatchCache) {
        String parentDirName = idToName.get(file.getParentId());
        String grandParentDirName = findGrandParentDirName(file, folderId, idToName);
        MediaFileNameParser.ParseResult parsed = MediaFileNameParser.parse(file.getName(), parentDirName, grandParentDirName);

        MediaItem item = existing == null ? new MediaItem() : existing;
        item.setUserId(directory.getUserId());
        item.setDirectoryId(directory.getId());
        item.setFileNodeId(file.getId());
        item.setFileSize(file.getSize());
        item.setFileLastModified(file.getLastModified());
        if (item.getProgressMs() == null) {
            item.setProgressMs(0L);
        }

        fillItemTypeAndSeries(item, file, folderFullIdPath, mediaType, parsed, idToName);
        fillProbeResult(item, file, username, idToName);
        fillMetadata(item, mediaType, parsed, seriesMatchCache);

        if (existing == null) {
            mediaItemMapper.insert(item);
        } else {
            mediaItemMapper.updateById(item);
        }
    }

    private void fillItemTypeAndSeries(MediaItem item, FileNode file, String folderFullIdPath, MediaType mediaType,
                                       MediaFileNameParser.ParseResult parsed, Map<String, String> idToName) {
        switch (mediaType) {
            case MOVIE -> {
                item.setItemType(MediaItemType.MOVIE.getCode());
                item.setSeriesName(null);
                item.setSeasonNo(null);
                item.setEpisodeNo(null);
            }
            case TV -> {
                item.setItemType(MediaItemType.EPISODE.getCode());
                item.setSeasonNo(parsed.seasonNo());
                item.setEpisodeNo(parsed.episodeNo());
                item.setSeriesName(resolveSeriesName(file, folderFullIdPath, parsed, idToName));
            }
            case OTHER -> {
                item.setItemType(MediaItemType.OTHER.getCode());
                item.setMatchStatus(MediaMatchStatus.NONE.getCode());
            }
        }
    }

    /**
     * 解析剧名：优先取目录下的一级子文件夹名，其次用文件名解析结果。
     */
    private String resolveSeriesName(FileNode file, String folderFullIdPath,
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

    private void fillProbeResult(MediaItem item, FileNode file, String username, Map<String, String> idToName) {
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

    private void fillMetadata(MediaItem item, MediaType mediaType, MediaFileNameParser.ParseResult parsed,
                              Map<String, String> seriesMatchCache) {
        if (mediaType == MediaType.OTHER) {
            return;
        }
        // 手动修正的条目保留原匹配
        if (MediaMatchStatus.MANUAL.getCode().equals(item.getMatchStatus()) && item.getMetadataId() != null) {
            return;
        }
        String title = mediaType == MediaType.TV ? item.getSeriesName() : parsed.title();
        // 同一部剧在一次扫描内只搜索一次 TMDB
        String cacheKey = mediaType.getCode() + ":" + title;
        if (seriesMatchCache.containsKey(cacheKey)) {
            String metadataId = seriesMatchCache.get(cacheKey);
            item.setMetadataId(metadataId);
            item.setMatchStatus(metadataId == null ? MediaMatchStatus.UNMATCHED.getCode() : MediaMatchStatus.MATCHED.getCode());
            return;
        }
        MediaMetadata metadata = tmdbService.autoMatch(mediaType.getCode(), title, parsed.year());
        if (metadata != null) {
            item.setMetadataId(metadata.getId());
            item.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
        } else {
            item.setMetadataId(null);
            item.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
        }
        seriesMatchCache.put(cacheKey, item.getMetadataId());
    }

    private String findGrandParentDirName(FileNode file, String folderId, Map<String, String> idToName) {
        String path = file.getPath();
        if (path == null) {
            return null;
        }
        String[] ids = path.split("\\.");
        // ids 末尾是父目录，倒数第二是祖父目录；父目录不能是视频目录本身
        if (ids.length >= 2 && !ids[ids.length - 1].equals(folderId)) {
            return idToName.get(ids[ids.length - 1]);
        }
        return null;
    }

    private boolean unchanged(MediaItem item, FileNode file) {
        return java.util.Objects.equals(item.getFileSize(), file.getSize())
                && java.util.Objects.equals(item.getFileLastModified(), file.getLastModified());
    }

    private void fillAncestorNames(FileNode folder, String userId, Map<String, String> idToName) {
        Set<String> ancestorIds = new HashSet<>();
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

    private void updateScanResult(MediaDirectory directory, String status, String error) {
        MediaDirectory update = new MediaDirectory();
        update.setId(directory.getId());
        update.setLastScanTime(LocalDateTime.now());
        update.setLastScanStatus(status);
        update.setLastScanError(error);
        if (directory.getScanCron() != null && !directory.getScanCron().isBlank()
                && CronExpression.isValidExpression(directory.getScanCron())) {
            update.setNextScanTime(CronExpression.parse(directory.getScanCron()).next(LocalDateTime.now()));
        }
        mediaDirectoryMapper.updateById(update);
    }
}
