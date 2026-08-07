package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.common.enums.MediaRefreshMode;
import com.fleyx.jcloud.common.enums.MediaScrapeStatus;
import com.fleyx.jcloud.common.enums.MediaType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.service.MediaScrapeService;
import com.fleyx.jcloud.service.support.MediaArtifactProbeSupport;
import com.fleyx.jcloud.service.support.MediaMovieScrapeSupport;
import com.fleyx.jcloud.service.support.MediaSeriesScrapeSupport;
import com.fleyx.jcloud.service.support.MediaTaskSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 媒体库削刮服务实现（新模型，ADR 0021 / issue #20，工单 08 拆分瘦身）。
 * <p>
 * 本类保留入口与整库调度：单条刷新（{@link #refreshItem}）按元数据归属类型分发给电影/剧集
 * 削刮支撑（{@link MediaMovieScrapeSupport}/{@link MediaSeriesScrapeSupport}）的刷新方法；
 * 整库削刮（{@link #scrapeOnce}）先经候选探测（{@link MediaArtifactProbeSupport}）批量收集
 * NFO/背景图产物缺失映射，逐行按 {@link #needScrape} 判定候选后调用对应支撑的削刮或产物补回，
 * 循环内 per-item 失败记录为 partial 不打断整库；结束时重算媒体库刮削状态
 * （{@link #markScraping}/{@link #updateScrapeResult}）。
 * 处理范围为「未匹配、不完整或 NFO 缺失」的非 manual 行（force 时全部非 manual 行）；
 * manual 永不覆盖匹配，但 manual/已匹配行 NFO 或背景图产物缺失时复用已有元数据行补回。
 * 与扫描按媒体库互斥（{@link MediaTaskSupport}），协作式取消。
 */
@Slf4j
@Service
public class MediaScrapeServiceImpl implements MediaScrapeService {

    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final MediaMovieMapper mediaMovieMapper;
    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final MediaTaskSupport mediaTaskSupport;
    private final MediaArtifactProbeSupport probeSupport;
    private final MediaMovieScrapeSupport movieScrapeSupport;
    private final MediaSeriesScrapeSupport seriesScrapeSupport;
    private final TaskExecutor taskExecutor;

    public MediaScrapeServiceImpl(MediaDirectoryMapper mediaDirectoryMapper, MediaMovieMapper mediaMovieMapper,
                                  MediaSeriesMapper mediaSeriesMapper, MediaMetadataMapper mediaMetadataMapper,
                                  MediaTaskSupport mediaTaskSupport, MediaArtifactProbeSupport probeSupport,
                                  MediaMovieScrapeSupport movieScrapeSupport,
                                  MediaSeriesScrapeSupport seriesScrapeSupport,
                                  @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.mediaDirectoryMapper = mediaDirectoryMapper;
        this.mediaMovieMapper = mediaMovieMapper;
        this.mediaSeriesMapper = mediaSeriesMapper;
        this.mediaMetadataMapper = mediaMetadataMapper;
        this.mediaTaskSupport = mediaTaskSupport;
        this.probeSupport = probeSupport;
        this.movieScrapeSupport = movieScrapeSupport;
        this.seriesScrapeSupport = seriesScrapeSupport;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public void submitScrape(String directoryId, String userId, boolean force) {
        MediaDirectory directory = mediaDirectoryMapper.selectById(directoryId);
        if (directory == null || !userId.equals(directory.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体库不存在");
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
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体库不存在");
        }
        mediaTaskSupport.enterOrThrow(directoryId, MediaTaskSupport.TASK_SCRAPE);
        try {
            mediaTaskSupport.clearCancel(directoryId);
            scrapeOnce(directory, force);
        } finally {
            mediaTaskSupport.exit(directoryId, MediaTaskSupport.TASK_SCRAPE);
        }
    }

    @Override
    public void refreshItem(String metadataId, String userId, MediaRefreshMode mode) {
        MediaMetadata metadata = mediaMetadataMapper.selectById(metadataId);
        if (metadata == null || !userId.equals(metadata.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "元数据不存在");
        }
        MediaMetadataOwnerType ownerType = MediaMetadataOwnerType.of(metadata.getOwnerType());
        if (ownerType == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "未知元数据归属类型");
        }
        switch (ownerType) {
            case MOVIE -> movieScrapeSupport.refreshMovieItem(metadata, userId, mode);
            case SERIES -> seriesScrapeSupport.refreshSeriesItem(metadata, userId, mode);
            default -> throw new BusinessException(ResultCode.BUSINESS_ERROR, "请刷新所属剧集");
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
            log.error("媒体库削刮失败: {}", directory.getId(), e);
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

    // ---------- 电影削刮 ----------

    private boolean scrapeMovies(MediaDirectory directory, boolean force) {
        boolean partial = false;
        List<MediaMovie> movies = mediaMovieMapper.selectList(new LambdaQueryWrapper<MediaMovie>()
                .eq(MediaMovie::getDirectoryId, directory.getId()));
        Map<String, Boolean> nfoMissingByMovie = probeSupport.collectMovieNfoMissing(movies);
        Map<String, Boolean> backdropMissingByMovie = probeSupport.collectMovieBackdropMissing(movies);
        for (MediaMovie movie : movies) {
            if (mediaTaskSupport.isCancelled(directory.getId())) {
                return partial;
            }
            boolean nfoMissing = nfoMissingByMovie.getOrDefault(movie.getId(), false);
            if (!needScrape(movie.getMatchStatus(), movie.getMetadataComplete(), force, nfoMissing)) {
                // manual/已完整行不削刮；完整性翻 false、NFO 缺失或背景图产物缺失时复用已有元数据补回
                // （不重新匹配、不改绑定，工单 03/05/07）
                if (!Boolean.TRUE.equals(movie.getMetadataComplete()) || nfoMissing
                        || backdropMissingByMovie.getOrDefault(movie.getId(), false)) {
                    movieScrapeSupport.refillMovieArtifacts(movie, false);
                }
                continue;
            }
            try {
                movieScrapeSupport.scrapeMovie(directory, movie, force);
            } catch (Exception e) {
                log.debug("电影削刮失败: movie={}, error={}", movie.getId(), e.getMessage());
                partial = true;
            }
        }
        return partial;
    }

    // ---------- 电视削刮 ----------

    private boolean scrapeSeries(MediaDirectory directory, boolean force) {
        boolean partial = false;
        List<MediaSeries> seriesList = mediaSeriesMapper.selectList(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getDirectoryId, directory.getId()));
        Map<String, Boolean> nfoMissingBySeries = probeSupport.collectSeriesNfoMissing(seriesList);
        Map<String, Boolean> backdropMissingBySeries = probeSupport.collectSeriesBackdropMissing(seriesList);
        for (MediaSeries series : seriesList) {
            if (mediaTaskSupport.isCancelled(directory.getId())) {
                return partial;
            }
            boolean nfoMissing = nfoMissingBySeries.getOrDefault(series.getId(), false);
            if (!needScrape(series.getMatchStatus(), series.getMetadataComplete(), force, nfoMissing)) {
                // manual/已完整行不削刮；完整性翻 false、NFO 缺失或背景图产物缺失时复用已有元数据补回
                // （不重新匹配、不改绑定，工单 03/05/07）
                if (!Boolean.TRUE.equals(series.getMetadataComplete()) || nfoMissing
                        || backdropMissingBySeries.getOrDefault(series.getId(), false)) {
                    seriesScrapeSupport.refillSeriesArtifacts(series, false);
                }
                continue;
            }
            try {
                seriesScrapeSupport.scrapeSeriesItem(directory, series, force);
            } catch (Exception e) {
                log.debug("剧集削刮失败: series={}, error={}", series.getSeriesName(), e.getMessage());
                partial = true;
            }
        }
        return partial;
    }

    // ---------- 通用 ----------

    /**
     * 是否需要削刮：manual 永不覆盖；force 处理全部非 manual，否则只处理「未匹配、不完整或 NFO 缺失」。
     * NFO 存在性纳入候选（工单 05）：NFO 被删除后条目经削刮自动重建；manual 行恒 false，
     * 其 NFO/背景图缺失重建走工单 03/07 的 refill 分支（persist 路径删跳过后自然重建）。
     */
    private boolean needScrape(String matchStatus, Boolean metadataComplete, boolean force, boolean nfoMissing) {
        if (MediaMatchStatus.MANUAL.getCode().equals(matchStatus)) {
            return false;
        }
        if (force) {
            return true;
        }
        return MediaMatchStatus.UNMATCHED.getCode().equals(matchStatus)
                || !Boolean.TRUE.equals(metadataComplete) || nfoMissing;
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
