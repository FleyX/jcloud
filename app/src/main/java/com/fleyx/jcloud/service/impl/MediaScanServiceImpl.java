package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaScanStatus;
import com.fleyx.jcloud.common.enums.MediaType;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.service.MediaScanService;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.MediaScanSupport;
import com.fleyx.jcloud.service.support.MediaSeriesSupport;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.MediaFileNameParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 媒体库扫描服务实现。
 * <p>
 * 扫描为协作式取消模型：{@link #requestCancel(String)} 仅标记取消，扫描循环逐文件检查；
 * 取消后如存在新的待扫描请求（目录修改触发的重扫），由同一线程接续执行。
 */
@Slf4j
@Service
public class MediaScanServiceImpl implements MediaScanService {

    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final MediaItemMapper mediaItemMapper;
    private final FileMapper fileMapper;
    private final UserMapper userMapper;
    private final TmdbService tmdbService;
    private final MediaSeriesSupport mediaSeriesSupport;
    private final MediaScanSupport mediaScanSupport;
    private final TaskExecutor taskExecutor;

    public MediaScanServiceImpl(MediaDirectoryMapper mediaDirectoryMapper, MediaItemMapper mediaItemMapper,
                                FileMapper fileMapper, UserMapper userMapper,
                                TmdbService tmdbService, MediaSeriesSupport mediaSeriesSupport,
                                MediaScanSupport mediaScanSupport,
                                @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.mediaDirectoryMapper = mediaDirectoryMapper;
        this.mediaItemMapper = mediaItemMapper;
        this.fileMapper = fileMapper;
        this.userMapper = userMapper;
        this.tmdbService = tmdbService;
        this.mediaSeriesSupport = mediaSeriesSupport;
        this.mediaScanSupport = mediaScanSupport;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 正在执行扫描循环的目录 ID 集合。
     */
    private final Set<String> activeScans = ConcurrentHashMap.newKeySet();

    /**
     * 已请求取消的目录 ID 集合。
     */
    private final Set<String> cancelledScans = ConcurrentHashMap.newKeySet();

    /**
     * 待执行的扫描请求（directoryId → 是否强制全量），同目录多次触发会合并。
     */
    private final Map<String, Boolean> pendingScans = new ConcurrentHashMap<>();

    @Override
    public void submitScan(String directoryId, String userId) {
        submitScan(directoryId, userId, false);
    }

    @Override
    public void submitScan(String directoryId, String userId, boolean force) {
        pendingScans.merge(directoryId, force, (a, b) -> a || b);
        taskExecutor.execute(() -> runLoop(directoryId));
    }

    @Override
    public void scan(String directoryId) {
        pendingScans.merge(directoryId, false, (a, b) -> a || b);
        runLoop(directoryId);
    }

    @Override
    public void requestCancel(String directoryId) {
        cancelledScans.add(directoryId);
    }

    /**
     * 扫描主循环：同一目录只跑一个线程，循环拾取待扫描请求直至清空。
     */
    private void runLoop(String directoryId) {
        if (!activeScans.add(directoryId)) {
            return;
        }
        try {
            Boolean force;
            while ((force = pendingScans.remove(directoryId)) != null) {
                cancelledScans.remove(directoryId);
                scanOnce(directoryId, force);
            }
        } finally {
            activeScans.remove(directoryId);
        }
    }

    private void scanOnce(String directoryId, boolean force) {
        MediaDirectory directory = mediaDirectoryMapper.selectById(directoryId);
        if (directory == null) {
            return;
        }
        markScanning(directory);
        try {
            ScanOutcome outcome = doScan(directory, force);
            switch (outcome) {
                case COMPLETED -> updateScanResult(directory, MediaScanStatus.COMPLETED.name(), null);
                case PARTIAL -> updateScanResult(directory, MediaScanStatus.PARTIAL.name(), "部分文件扫描失败");
                case CANCELLED -> updateScanResult(directory, MediaScanStatus.FAILED.name(), "扫描已中断");
            }
        } catch (Exception e) {
            log.error("媒体目录扫描失败: {}", directoryId, e);
            updateScanResult(directory, MediaScanStatus.FAILED.name(), e.getMessage());
        }
    }

    private enum ScanOutcome {
        COMPLETED, PARTIAL, CANCELLED
    }

    private ScanOutcome doScan(MediaDirectory directory, boolean force) {
        String userId = directory.getUserId();
        FileNode folder = fileMapper.selectById(directory.getFileNodeId());
        if (folder == null || !userId.equals(folder.getUserId()) || !"folder".equals(folder.getType())) {
            throw new IllegalStateException("视频目录对应的文件夹不存在");
        }
        String username = userMapper.selectById(userId).getUsername();

        List<FileNode> nodes = fileMapper.selectByIdPathPrefix(userId, folder.getPath(), folder.getId());
        Map<String, String> idToName = FilePathUtil.buildNameCache(nodes);
        mediaScanSupport.fillAncestorNames(folder, userId, idToName);
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
        Map<String, MediaSeries> seriesCache = new HashMap<>();
        Map<String, MediaSeason> seasonCache = new HashMap<>();
        Set<String> touchedSeriesIds = new HashSet<>();
        boolean partial = false;
        Set<String> seenFileNodeIds = new HashSet<>();
        for (FileNode file : videoFiles) {
            if (cancelledScans.contains(directory.getId())) {
                log.info("目录扫描被中断: {}", directory.getId());
                return ScanOutcome.CANCELLED;
            }
            seenFileNodeIds.add(file.getId());
            MediaItem existing = existingMap.get(file.getId());
            if (existing != null && !force && mediaScanSupport.unchanged(existing, file)
                    && seriesLinked(existing, mediaType)
                    && seriesNameUpToDate(existing, file, folder.getId(), folderFullIdPath, mediaType, idToName)) {
                continue;
            }
            try {
                scanFile(directory, folder.getId(), folderFullIdPath, file, existing, mediaType, idToName,
                        username, seriesMatchCache, seriesCache, seasonCache, touchedSeriesIds);
            } catch (Exception e) {
                log.warn("扫描文件失败: {}, {}", file.getName(), e.getMessage());
                partial = true;
            }
        }
        // 清理已消失文件的条目
        for (MediaItem item : existingMap.values()) {
            if (!seenFileNodeIds.contains(item.getFileNodeId())) {
                mediaItemMapper.deleteById(item.getId());
                if (item.getSeriesId() != null) {
                    touchedSeriesIds.add(item.getSeriesId());
                }
            }
        }
        mediaSeriesSupport.recalcMinFileLastModified(touchedSeriesIds);
        mediaSeriesSupport.cleanupOrphans(userId);
        return partial ? ScanOutcome.PARTIAL : ScanOutcome.COMPLETED;
    }

    /**
     * 增量跳过的前提：剧集条目必须已挂到剧/季（兼容旧数据）。
     */
    private boolean seriesLinked(MediaItem item, MediaType mediaType) {
        return mediaType != MediaType.TV || item.getSeriesId() != null;
    }

    /**
     * 增量跳过的前提：剧集解析出的剧名与条目一致。
     * 文件移动/重命名不会改变 size 和 mtime，需重新解析剧名兜底，防止剧分组过期。
     */
    private boolean seriesNameUpToDate(MediaItem item, FileNode file, String folderId, String folderFullIdPath,
                                       MediaType mediaType, Map<String, String> idToName) {
        if (mediaType != MediaType.TV) {
            return true;
        }
        String parentDirName = idToName.get(file.getParentId());
        String grandParentDirName = mediaScanSupport.findGrandParentDirName(file, folderId, idToName);
        MediaFileNameParser.ParseResult parsed = MediaFileNameParser.parse(file.getName(), parentDirName, grandParentDirName);
        String resolved = mediaScanSupport.resolveSeriesName(file, folderFullIdPath, parsed, idToName);
        return Objects.equals(item.getSeriesName(), resolved);
    }

    private void scanFile(MediaDirectory directory, String folderId, String folderFullIdPath, FileNode file,
                          MediaItem existing, MediaType mediaType, Map<String, String> idToName,
                          String username, Map<String, String> seriesMatchCache,
                          Map<String, MediaSeries> seriesCache, Map<String, MediaSeason> seasonCache,
                          Set<String> touchedSeriesIds) {
        String parentDirName = idToName.get(file.getParentId());
        String grandParentDirName = mediaScanSupport.findGrandParentDirName(file, folderId, idToName);
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

        MediaSeries series = fillItemTypeAndSeries(item, file, folderFullIdPath, mediaType, parsed,
                idToName, directory.getUserId(), seriesCache, seasonCache);
        mediaScanSupport.fillProbeResult(item, file, username, idToName);
        fillMetadata(item, mediaType, parsed, seriesMatchCache);
        if (series != null) {
            mediaSeriesSupport.applySeriesMatch(series, item.getMetadataId());
            touchedSeriesIds.add(series.getId());
        }

        if (existing == null) {
            mediaItemMapper.insert(item);
        } else {
            mediaItemMapper.updateById(item);
        }
    }

    private MediaSeries fillItemTypeAndSeries(MediaItem item, FileNode file, String folderFullIdPath, MediaType mediaType,
                                              MediaFileNameParser.ParseResult parsed, Map<String, String> idToName,
                                              String userId, Map<String, MediaSeries> seriesCache,
                                              Map<String, MediaSeason> seasonCache) {
        switch (mediaType) {
            case MOVIE -> {
                item.setItemType(MediaItemType.MOVIE.getCode());
                clearSeriesFields(item);
                return null;
            }
            case TV -> {
                item.setItemType(MediaItemType.EPISODE.getCode());
                item.setSeasonNo(parsed.seasonNo());
                item.setEpisodeNo(parsed.episodeNo());
                String seriesName = mediaScanSupport.resolveSeriesName(file, folderFullIdPath, parsed, idToName);
                item.setSeriesName(seriesName);
                if (seriesName == null) {
                    item.setSeriesId(null);
                    item.setSeasonId(null);
                    return null;
                }
                MediaSeries series = mediaSeriesSupport.getOrCreateSeries(userId, seriesName, seriesCache);
                MediaSeason season = mediaSeriesSupport.getOrCreateSeason(series.getId(), parsed.seasonNo(), seasonCache);
                item.setSeriesId(series.getId());
                item.setSeasonId(season.getId());
                return series;
            }
            case OTHER -> {
                item.setItemType(MediaItemType.OTHER.getCode());
                clearSeriesFields(item);
                item.setMatchStatus(MediaMatchStatus.NONE.getCode());
                return null;
            }
        }
        return null;
    }

    private void clearSeriesFields(MediaItem item) {
        item.setSeriesName(null);
        item.setSeriesId(null);
        item.setSeasonId(null);
        item.setSeasonNo(null);
        item.setEpisodeNo(null);
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

    /**
     * 扫描开始时标记目录为扫描中。
     */
    private void markScanning(MediaDirectory directory) {
        MediaDirectory update = new MediaDirectory();
        update.setId(directory.getId());
        update.setLastScanStatus(MediaScanStatus.SCANNING.name());
        update.setLastScanError(null);
        mediaDirectoryMapper.updateById(update);
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
