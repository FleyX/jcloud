package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaScrapeStatus;
import com.fleyx.jcloud.common.enums.MediaType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.service.MediaScrapeService;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.MediaSeriesSupport;
import com.fleyx.jcloud.service.support.MediaTaskSupport;
import com.fleyx.jcloud.util.MediaFileNameParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 媒体库削刮服务实现。
 * <p>
 * 电影以条目为单位削刮：先文件名解析结果，失败用直接父目录名兜底；
 * 电视以剧为单位削刮：一次匹配应用到全剧非手动修正的集，并拉取本地存在的季/集元数据。
 * 手动修正的条目与剧永远跳过。与扫描按目录互斥（{@link MediaTaskSupport}），协作式取消。
 */
@Slf4j
@Service
public class MediaScrapeServiceImpl implements MediaScrapeService {

    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final MediaItemMapper mediaItemMapper;
    private final MediaSeriesMapper mediaSeriesMapper;
    private final FileMapper fileMapper;
    private final TmdbService tmdbService;
    private final MediaSeriesSupport mediaSeriesSupport;
    private final MediaTaskSupport mediaTaskSupport;
    private final TaskExecutor taskExecutor;

    public MediaScrapeServiceImpl(MediaDirectoryMapper mediaDirectoryMapper, MediaItemMapper mediaItemMapper,
                                  MediaSeriesMapper mediaSeriesMapper, FileMapper fileMapper,
                                  TmdbService tmdbService, MediaSeriesSupport mediaSeriesSupport,
                                  MediaTaskSupport mediaTaskSupport,
                                  @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.mediaDirectoryMapper = mediaDirectoryMapper;
        this.mediaItemMapper = mediaItemMapper;
        this.mediaSeriesMapper = mediaSeriesMapper;
        this.fileMapper = fileMapper;
        this.tmdbService = tmdbService;
        this.mediaSeriesSupport = mediaSeriesSupport;
        this.mediaTaskSupport = mediaTaskSupport;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public void submitScrape(String directoryId, String userId, boolean force) {
        MediaDirectory directory = mediaDirectoryMapper.selectById(directoryId);
        if (directory == null || !userId.equals(directory.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "视频目录不存在");
        }
        mediaTaskSupport.enterOrThrow(directoryId, MediaTaskSupport.TASK_SCRAPE);
        taskExecutor.execute(() -> {
            try {
                mediaTaskSupport.clearCancel(directoryId);
                scrapeOnce(directory, force);
            } finally {
                mediaTaskSupport.exit(directoryId, MediaTaskSupport.TASK_SCRAPE);
            }
        });
    }

    @Override
    public void scrape(String directoryId, String userId, boolean force) {
        MediaDirectory directory = mediaDirectoryMapper.selectById(directoryId);
        if (directory == null || !userId.equals(directory.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "视频目录不存在");
        }
        mediaTaskSupport.enterOrThrow(directoryId, MediaTaskSupport.TASK_SCRAPE);
        try {
            mediaTaskSupport.clearCancel(directoryId);
            scrapeOnce(directory, force);
        } finally {
            mediaTaskSupport.exit(directoryId, MediaTaskSupport.TASK_SCRAPE);
        }
    }

    private void scrapeOnce(MediaDirectory directory, boolean force) {
        markScraping(directory);
        try {
            boolean partial = doScrape(directory, force);
            if (mediaTaskSupport.isCancelled(directory.getId())) {
                updateScrapeResult(directory, MediaScrapeStatus.FAILED.name(), "削刮已中断");
            } else {
                updateScrapeResult(directory, partial ? MediaScrapeStatus.PARTIAL.name() : MediaScrapeStatus.COMPLETED.name(),
                        partial ? "部分条目削刮失败" : null);
            }
        } catch (Exception e) {
            log.error("媒体目录削刮失败: {}", directory.getId(), e);
            updateScrapeResult(directory, MediaScrapeStatus.FAILED.name(), e.getMessage());
        }
    }

    /**
     * @return 是否存在部分失败
     */
    private boolean doScrape(MediaDirectory directory, boolean force) {
        MediaType mediaType = MediaType.of(directory.getMediaType());
        if (mediaType == MediaType.OTHER) {
            return false;
        }
        if (mediaType == MediaType.MOVIE) {
            return scrapeMovies(directory, force);
        }
        return scrapeSeries(directory, force);
    }

    /**
     * 电影目录削刮：逐条目匹配，文件名失败时用直接父目录名兜底。
     */
    private boolean scrapeMovies(MediaDirectory directory, boolean force) {
        boolean partial = false;
        List<MediaItem> items = mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                .eq(MediaItem::getDirectoryId, directory.getId())
                .eq(MediaItem::getItemType, MediaItemType.MOVIE.getCode()));
        for (MediaItem item : items) {
            if (mediaTaskSupport.isCancelled(directory.getId())) {
                return partial;
            }
            if (!needScrape(item.getMatchStatus(), force)) {
                continue;
            }
            try {
                scrapeMovie(directory, item);
            } catch (Exception e) {
                log.warn("电影削刮失败: itemId={}, error={}", item.getId(), e.getMessage());
                partial = true;
            }
        }
        return partial;
    }

    private void scrapeMovie(MediaDirectory directory, MediaItem item) {
        FileNode file = fileMapper.selectById(item.getFileNodeId());
        if (file == null) {
            return;
        }
        MediaFileNameParser.ParseResult parsed = MediaFileNameParser.parse(file.getName(), null, null);
        MediaMetadata metadata = tmdbService.autoMatch(MediaType.MOVIE.getCode(), parsed.title(), parsed.year());
        if (metadata == null && !directory.getFileNodeId().equals(file.getParentId())) {
            // 直接父目录名兜底（父目录为视频目录本身时不再兜底）
            FileNode parent = fileMapper.selectById(file.getParentId());
            String fallback = parent == null ? "" : MediaFileNameParser.cleanTitle(parent.getName());
            if (!fallback.isBlank() && !fallback.equals(parsed.title())) {
                metadata = tmdbService.autoMatch(MediaType.MOVIE.getCode(), fallback, parsed.year());
            }
        }
        applyMovieMatch(item, metadata);
    }

    private void applyMovieMatch(MediaItem item, MediaMetadata metadata) {
        MediaItem update = new MediaItem();
        update.setId(item.getId());
        update.setMetadataId(metadata == null ? null : metadata.getId());
        update.setMatchStatus(metadata == null ? MediaMatchStatus.UNMATCHED.getCode() : MediaMatchStatus.MATCHED.getCode());
        mediaItemMapper.updateById(update);
    }

    /**
     * 电视目录削刮：逐剧匹配，成功则应用到全剧并拉取季/集元数据。
     */
    private boolean scrapeSeries(MediaDirectory directory, boolean force) {
        boolean partial = false;
        List<String> seriesIds = mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                        .eq(MediaItem::getDirectoryId, directory.getId())
                        .eq(MediaItem::getItemType, MediaItemType.EPISODE.getCode())
                        .isNotNull(MediaItem::getSeriesId)
                        .select(MediaItem::getSeriesId))
                .stream().map(MediaItem::getSeriesId).distinct().toList();
        for (String seriesId : seriesIds) {
            if (mediaTaskSupport.isCancelled(directory.getId())) {
                return partial;
            }
            MediaSeries series = mediaSeriesMapper.selectById(seriesId);
            if (series == null || !needScrape(series.getMatchStatus(), force)) {
                continue;
            }
            try {
                MediaMetadata metadata = tmdbService.autoMatch(MediaType.TV.getCode(), series.getSeriesName(), null);
                if (metadata == null) {
                    mediaSeriesSupport.applySeriesMatch(series, null);
                    continue;
                }
                mediaSeriesSupport.applySeriesMatch(series, metadata.getId());
                mediaSeriesSupport.applySeriesMetadata(series, metadata, MediaMatchStatus.MATCHED.getCode());
            } catch (Exception e) {
                log.warn("剧集削刮失败: series={}, error={}", series.getSeriesName(), e.getMessage());
                partial = true;
            }
        }
        return partial;
    }

    /**
     * 是否需要削刮：手动修正的永远跳过；force 处理全部非手动，否则只处理未匹配。
     */
    private boolean needScrape(String matchStatus, boolean force) {
        if (MediaMatchStatus.MANUAL.getCode().equals(matchStatus)) {
            return false;
        }
        return force || MediaMatchStatus.UNMATCHED.getCode().equals(matchStatus);
    }

    private void markScraping(MediaDirectory directory) {
        MediaDirectory update = new MediaDirectory();
        update.setId(directory.getId());
        update.setLastScrapeStatus(MediaScrapeStatus.SCRAPING.name());
        update.setLastScrapeError(null);
        mediaDirectoryMapper.updateById(update);
    }

    private void updateScrapeResult(MediaDirectory directory, String status, String error) {
        MediaDirectory update = new MediaDirectory();
        update.setId(directory.getId());
        update.setLastScrapeTime(LocalDateTime.now());
        update.setLastScrapeStatus(status);
        update.setLastScrapeError(error);
        mediaDirectoryMapper.updateById(update);
    }
}
