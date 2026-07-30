package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.service.MediaScanService;
import com.fleyx.jcloud.service.MediaScrapeService;
import com.fleyx.jcloud.service.support.MediaScanSupport;
import com.fleyx.jcloud.service.support.MediaSeriesSupport;
import com.fleyx.jcloud.service.support.MediaTaskSupport;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 媒体库扫描服务实现。
 * <p>
 * 扫描只负责文件事实：按文件变更哈希 diff 存量条目、ffprobe 探测、维护剧/季归属（含首播年份回填），
 * 不访问 TMDB。变化条目的非手动匹配状态会被重置为未匹配，所属剧的匹配状态同步重置；
 * 扫描完成后自动提交一次非强制削刮（削刮仍是独立任务，扫描本身不访问 TMDB）。
 * <p>
 * 并发模型：扫描与削刮按目录互斥（{@link MediaTaskSupport}），协作式取消，
 * 同一目录的多次扫描请求通过 {@link #pendingScans} 合并，由单线程循环拾取。
 */
@Slf4j
@Service
public class MediaScanServiceImpl implements MediaScanService {

    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final MediaItemMapper mediaItemMapper;
    private final FileMapper fileMapper;
    private final UserMapper userMapper;
    private final MediaSeriesSupport mediaSeriesSupport;
    private final MediaScanSupport mediaScanSupport;
    private final MediaTaskSupport mediaTaskSupport;
    private final MediaScrapeService mediaScrapeService;
    private final TaskExecutor taskExecutor;

    public MediaScanServiceImpl(MediaDirectoryMapper mediaDirectoryMapper, MediaItemMapper mediaItemMapper,
                                FileMapper fileMapper, UserMapper userMapper,
                                MediaSeriesSupport mediaSeriesSupport, MediaScanSupport mediaScanSupport,
                                MediaTaskSupport mediaTaskSupport, MediaScrapeService mediaScrapeService,
                                @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.mediaDirectoryMapper = mediaDirectoryMapper;
        this.mediaItemMapper = mediaItemMapper;
        this.fileMapper = fileMapper;
        this.userMapper = userMapper;
        this.mediaSeriesSupport = mediaSeriesSupport;
        this.mediaScanSupport = mediaScanSupport;
        this.mediaTaskSupport = mediaTaskSupport;
        this.mediaScrapeService = mediaScrapeService;
        this.taskExecutor = taskExecutor;
    }

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
        mediaTaskSupport.requestCancel(directoryId);
    }

    /**
     * 扫描主循环：同一目录只跑一个线程，循环拾取待扫描请求直至清空。
     * 目录被削刮任务占用时延迟重试。扫描成功完成后自动提交一次非强制削刮。
     */
    private void runLoop(String directoryId) {
        if (!mediaTaskSupport.enter(directoryId, MediaTaskSupport.TASK_SCAN)) {
            if (pendingScans.containsKey(directoryId)) {
                log.info("目录任务被占用，延迟重试扫描: {}", directoryId);
                taskExecutor.execute(() -> {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    runLoop(directoryId);
                });
            }
            return;
        }
        boolean completed = false;
        try {
            Boolean force;
            while ((force = pendingScans.remove(directoryId)) != null) {
                mediaTaskSupport.clearCancel(directoryId);
                completed |= scanOnce(directoryId, force);
            }
        } finally {
            mediaTaskSupport.exit(directoryId, MediaTaskSupport.TASK_SCAN);
        }
        if (completed) {
            submitScrapeAfterScan(directoryId);
        }
    }

    /**
     * 扫描完成后自动提交非强制削刮（只处理未匹配内容）。提交失败仅记日志。
     */
    private void submitScrapeAfterScan(String directoryId) {
        MediaDirectory directory = mediaDirectoryMapper.selectById(directoryId);
        if (directory == null) {
            return;
        }
        try {
            mediaScrapeService.submitScrape(directoryId, directory.getUserId(), false);
        } catch (Exception e) {
            log.info("扫描后自动削刮提交失败: {}, {}", directoryId, e.getMessage());
        }
    }

    /**
     * @return 扫描是否成功完成（完成或部分完成；中断、异常视为未完成）
     */
    private boolean scanOnce(String directoryId, boolean force) {
        MediaDirectory directory = mediaDirectoryMapper.selectById(directoryId);
        if (directory == null) {
            return false;
        }
        markScanning(directory);
        try {
            ScanOutcome outcome = doScan(directory, force);
            switch (outcome) {
                case COMPLETED -> updateScanResult(directory, MediaScanStatus.COMPLETED.name(), null);
                case PARTIAL -> updateScanResult(directory, MediaScanStatus.PARTIAL.name(), "部分文件扫描失败");
                case CANCELLED -> updateScanResult(directory, MediaScanStatus.FAILED.name(), "扫描已中断");
            }
            return outcome != ScanOutcome.CANCELLED;
        } catch (Exception e) {
            log.error("媒体目录扫描失败: {}", directoryId, e);
            updateScanResult(directory, MediaScanStatus.FAILED.name(), e.getMessage());
            return false;
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
        Map<String, MediaSeries> seriesCache = new HashMap<>();
        Map<String, MediaSeason> seasonCache = new HashMap<>();
        Map<String, Integer> seriesYears = new HashMap<>();
        Set<String> touchedSeriesIds = new HashSet<>();
        boolean partial = false;
        Set<String> seenFileNodeIds = new HashSet<>();
        for (FileNode file : videoFiles) {
            if (mediaTaskSupport.isCancelled(directory.getId())) {
                log.info("目录扫描被中断: {}", directory.getId());
                return ScanOutcome.CANCELLED;
            }
            // 电视目录：不符合固定三层结构的文件忽略（存量条目在清理阶段删除）
            MediaScanSupport.TvLocation tvLocation = null;
            if (mediaType == MediaType.TV) {
                tvLocation = mediaScanSupport.resolveTvLocation(file, folderFullIdPath, idToName);
                if (tvLocation == null) {
                    continue;
                }
                seriesYears.put(tvLocation.seriesName(), tvLocation.releaseYear());
            }
            seenFileNodeIds.add(file.getId());
            String fileHash = mediaScanSupport.computeFileHash(file, folderFullIdPath, idToName);
            MediaItem existing = existingMap.get(file.getId());
            if (existing != null && !force && mediaScanSupport.unchanged(existing, fileHash)) {
                continue;
            }
            try {
                scanFile(directory, file, fileHash, existing, mediaType, tvLocation, idToName,
                        username, seriesCache, seasonCache, touchedSeriesIds);
            } catch (Exception e) {
                log.warn("扫描文件失败: {}, {}", file.getName(), e.getMessage());
                partial = true;
            }
        }
        // 清理已消失或被忽略文件的条目
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
        syncSeriesYears(userId, mediaType, seriesYears, seriesCache);
        return partial ? ScanOutcome.PARTIAL : ScanOutcome.COMPLETED;
    }

    /**
     * 回填剧的首播年份（含未变化剧集，覆盖存量数据与剧文件夹改名场景）。
     */
    private void syncSeriesYears(String userId, MediaType mediaType, Map<String, Integer> seriesYears,
                                 Map<String, MediaSeries> seriesCache) {
        if (mediaType != MediaType.TV || seriesYears.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> entry : seriesYears.entrySet()) {
            MediaSeries series = mediaSeriesSupport.getOrCreateSeries(userId, entry.getKey(), seriesCache);
            mediaSeriesSupport.syncReleaseYear(series, entry.getValue());
        }
    }

    private void scanFile(MediaDirectory directory, FileNode file, String fileHash,
                          MediaItem existing, MediaType mediaType, MediaScanSupport.TvLocation tvLocation,
                          Map<String, String> idToName, String username,
                          Map<String, MediaSeries> seriesCache, Map<String, MediaSeason> seasonCache,
                          Set<String> touchedSeriesIds) {
        MediaItem item = existing == null ? new MediaItem() : existing;
        item.setUserId(directory.getUserId());
        item.setDirectoryId(directory.getId());
        item.setFileNodeId(file.getId());
        item.setFileSize(file.getSize());
        item.setFileLastModified(file.getLastModified());
        item.setFileHash(fileHash);
        if (item.getProgressMs() == null) {
            item.setProgressMs(0L);
        }

        MediaSeries series = fillItemTypeAndSeries(item, mediaType, tvLocation, file, directory.getUserId(),
                seriesCache, seasonCache, touchedSeriesIds);
        mediaScanSupport.fillProbeResult(item, file, username, idToName);
        boolean matchReset = resetMatchIfNotManual(item, mediaType);

        if (existing == null) {
            mediaItemMapper.insert(item);
        } else {
            mediaItemMapper.updateById(item);
            if (matchReset) {
                // updateById 忽略 null 字段，条目元数据关联需显式清空
                clearItemMetadataId(item.getId());
                if (series != null) {
                    mediaSeriesSupport.resetMatch(series);
                }
            }
        }
    }

    /**
     * 显式清空条目的元数据关联（updateById 无法写入 null）。
     */
    private void clearItemMetadataId(String itemId) {
        mediaItemMapper.update(null, new LambdaUpdateWrapper<MediaItem>()
                .eq(MediaItem::getId, itemId)
                .set(MediaItem::getMetadataId, null));
    }

    /**
     * 填充条目类型与剧/季归属。
     *
     * @return 条目所属的剧（电视目录），其他类型返回 null
     */
    private MediaSeries fillItemTypeAndSeries(MediaItem item, MediaType mediaType, MediaScanSupport.TvLocation tvLocation,
                                              FileNode file, String userId,
                                              Map<String, MediaSeries> seriesCache, Map<String, MediaSeason> seasonCache,
                                              Set<String> touchedSeriesIds) {
        switch (mediaType) {
            case MOVIE -> {
                item.setItemType(MediaItemType.MOVIE.getCode());
                clearSeriesFields(item);
                return null;
            }
            case TV -> {
                item.setItemType(MediaItemType.EPISODE.getCode());
                Integer episodeNo = MediaFileNameParser.parse(file.getName(), null, null).episodeNo();
                item.setEpisodeNo(episodeNo);
                item.setSeasonNo(tvLocation.seasonNo());
                item.setSeriesName(tvLocation.seriesName());
                MediaSeries series = mediaSeriesSupport.getOrCreateSeries(userId, tvLocation.seriesName(), seriesCache);
                MediaSeason season = mediaSeriesSupport.getOrCreateSeason(series.getId(), tvLocation.seasonNo(), seasonCache);
                item.setSeriesId(series.getId());
                item.setSeasonId(season.getId());
                touchedSeriesIds.add(series.getId());
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

    /**
     * 变化的条目重置匹配状态：手动修正的保留，其余清空元数据待削刮。
     *
     * @return 是否发生了重置
     */
    private boolean resetMatchIfNotManual(MediaItem item, MediaType mediaType) {
        if (mediaType == MediaType.OTHER) {
            return false;
        }
        if (MediaMatchStatus.MANUAL.getCode().equals(item.getMatchStatus()) && item.getMetadataId() != null) {
            return false;
        }
        item.setMetadataId(null);
        item.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
        return true;
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
