package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.common.enums.MediaScrapeStatus;
import com.fleyx.jcloud.common.enums.MediaType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaSeriesV2Mapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaMetadataV2;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaSeriesV2;
import com.fleyx.jcloud.service.MediaScrapeService;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.MediaArtworkPersistSupport;
import com.fleyx.jcloud.service.support.MediaArtworkPersistV2Support;
import com.fleyx.jcloud.service.support.MediaMetadataCompleteSupport;
import com.fleyx.jcloud.service.support.MediaMetadataV2Support;
import com.fleyx.jcloud.service.support.MediaNfoSupport;
import com.fleyx.jcloud.service.support.MediaPlaybackResolveSupport;
import com.fleyx.jcloud.service.support.MediaTaskSupport;
import com.fleyx.jcloud.service.support.MediaTvScrapeSupport;
import com.fleyx.jcloud.util.MediaFileNameParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 媒体库削刮服务实现（新模型，ADR 0021 / issue #20）。
 * <p>
 * 削刮为本地优先：视频同目录存在 NFO 或本地媒体图片时完全信任本地内容、不请求 TMDB；
 * 本地缺失时电影以电影行为单位（先文件名解析结果，失败用电影文件夹名兜底）、
 * 电视以剧为单位（一次匹配应用到全剧，季/集元数据按剧级匹配派生，见 {@link MediaTvScrapeSupport}）。
 * 削刮成功后立即把元数据写回视频目录的 NFO 与图片，落盘失败不影响元数据入库。
 * 处理范围为「未匹配或不完整」的非 manual 行（force 时全部非 manual 行），manual 永不覆盖；
 * 每次削刮结束后重算电影/剧集行的元数据完整性（{@link MediaMetadataCompleteSupport}）。
 * 与扫描按媒体库互斥（{@link MediaTaskSupport}），协作式取消。
 */
@Slf4j
@Service
public class MediaScrapeServiceImpl implements MediaScrapeService {

    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final MediaMovieMapper mediaMovieMapper;
    private final MediaSeriesV2Mapper mediaSeriesV2Mapper;
    private final FileMapper fileMapper;
    private final TmdbService tmdbService;
    private final MediaTaskSupport mediaTaskSupport;
    private final MediaNfoSupport mediaNfoSupport;
    private final MediaMetadataV2Support metadataV2Support;
    private final MediaMetadataCompleteSupport completeSupport;
    private final MediaTvScrapeSupport mediaTvScrapeSupport;
    private final MediaArtworkPersistSupport persistSupport;
    private final MediaArtworkPersistV2Support artworkPersistV2Support;
    private final MediaPlaybackResolveSupport playbackResolveSupport;
    private final TaskExecutor taskExecutor;

    public MediaScrapeServiceImpl(MediaDirectoryMapper mediaDirectoryMapper, MediaMovieMapper mediaMovieMapper,
                                  MediaSeriesV2Mapper mediaSeriesV2Mapper, FileMapper fileMapper,
                                  TmdbService tmdbService, MediaTaskSupport mediaTaskSupport,
                                  MediaNfoSupport mediaNfoSupport, MediaMetadataV2Support metadataV2Support,
                                  MediaMetadataCompleteSupport completeSupport,
                                  MediaTvScrapeSupport mediaTvScrapeSupport,
                                  MediaArtworkPersistSupport persistSupport,
                                  MediaArtworkPersistV2Support artworkPersistV2Support,
                                  MediaPlaybackResolveSupport playbackResolveSupport,
                                  @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.mediaDirectoryMapper = mediaDirectoryMapper;
        this.mediaMovieMapper = mediaMovieMapper;
        this.mediaSeriesV2Mapper = mediaSeriesV2Mapper;
        this.fileMapper = fileMapper;
        this.tmdbService = tmdbService;
        this.mediaTaskSupport = mediaTaskSupport;
        this.mediaNfoSupport = mediaNfoSupport;
        this.metadataV2Support = metadataV2Support;
        this.completeSupport = completeSupport;
        this.mediaTvScrapeSupport = mediaTvScrapeSupport;
        this.persistSupport = persistSupport;
        this.artworkPersistV2Support = artworkPersistV2Support;
        this.playbackResolveSupport = playbackResolveSupport;
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
        for (MediaMovie movie : movies) {
            if (mediaTaskSupport.isCancelled(directory.getId())) {
                return partial;
            }
            if (!needScrape(movie.getMatchStatus(), movie.getMetadataComplete(), force)) {
                continue;
            }
            try {
                scrapeMovie(directory, movie);
            } catch (Exception e) {
                log.warn("电影削刮失败: movie={}, error={}", movie.getId(), e.getMessage());
                partial = true;
            }
        }
        return partial;
    }

    /**
     * 单部电影削刮：本地优先（同名 .nfo / poster / fanart），无本地内容时 TMDB
     * 先文件名解析结果、失败用电影文件夹名兜底。
     */
    private void scrapeMovie(MediaDirectory directory, MediaMovie movie) {
        MediaMetadataV2 local = scrapeMovieLocalNfo(movie);
        MediaMetadataV2 metadata = local;
        if (metadata == null) {
            MediaMovieFile file = playbackResolveSupport.pickMovieFile(movie);
            FileNode video = file == null ? null : fileMapper.selectById(file.getFileNodeId());
            if (video != null) {
                MediaFileNameParser.ParseResult parsed = MediaFileNameParser.parse(video.getName(), null, null);
                metadata = tmdbService.autoMatchV2(directory.getUserId(), MediaType.MOVIE.getCode(),
                        parsed.title(), parsed.year());
                // 电影文件夹名兜底（扫描已把文件夹名清理为 movie.title）
                String fallback = movie.getTitle();
                if (metadata == null && !fallback.isBlank() && !fallback.equals(parsed.title())) {
                    metadata = tmdbService.autoMatchV2(directory.getUserId(), MediaType.MOVIE.getCode(),
                            fallback, parsed.year());
                }
            }
        }
        MediaMetadataV2 bound = applyMovieMatch(movie, metadata);
        if (bound != null) {
            artworkPersistV2Support.persistMovieV2(movie, bound);
        }
        completeSupport.refreshMovieComplete(movie);
    }

    /**
     * 电影本地优先削刮：视频同目录存在与视频同名的 .nfo 时完全信任本地内容，不请求 TMDB；
     * 无 NFO 但存在本地图片（poster.jpg/fanart.jpg）时同样本地优先，构建缺失文本字段的
     * local_nfo 元数据（标记不完整），图片绑定本地文件。
     *
     * @return 已绑定 owner 的本地元数据，无 NFO 且无本地图片时返回 null
     */
    private MediaMetadataV2 scrapeMovieLocalNfo(MediaMovie movie) {
        FileNode folder = fileMapper.selectById(movie.getFolderNodeId());
        MediaMovieFile file = playbackResolveSupport.pickMovieFile(movie);
        FileNode video = file == null ? null : fileMapper.selectById(file.getFileNodeId());
        if (folder == null || video == null) {
            return null;
        }
        String userId = movie.getUserId();
        FileNode nfoNode = persistSupport.findChildFile(userId, folder.getId(),
                mediaNfoSupport.nfoNameOf(video.getName()));
        MediaNfoSupport.NfoData data = nfoNode == null ? null : readNfo(nfoNode);
        FileNode poster = persistSupport.findChildFile(userId, folder.getId(), MediaNfoSupport.POSTER_JPG);
        FileNode fanart = persistSupport.findChildFile(userId, folder.getId(), MediaNfoSupport.FANART_JPG);
        if (data == null && poster == null && fanart == null) {
            return null;
        }
        return metadataV2Support.upsertLocal(MediaMetadataOwnerType.MOVIE.getCode(), movie.getId(), userId,
                data == null ? MediaNfoSupport.emptyData(MediaType.MOVIE.getCode()) : data,
                poster == null ? null : poster.getId(), fanart == null ? null : fanart.getId());
    }

    /**
     * 应用电影匹配结果并返回绑定后的元数据行：本地元数据已绑定 owner 直接回写关联；
     * TMDB 游离元数据先绑定 owner 再回写；匹配失败清空元数据行（owner 指针不留孤儿）并置未匹配。
     *
     * @return 绑定后的元数据行；未匹配时返回 null
     */
    private MediaMetadataV2 applyMovieMatch(MediaMovie movie, MediaMetadataV2 metadata) {
        String metadataId;
        String matchStatus;
        MediaMetadataV2 bound;
        if (metadata == null) {
            metadataV2Support.deleteByOwner(MediaMetadataOwnerType.MOVIE.getCode(), movie.getId());
            metadataId = null;
            matchStatus = MediaMatchStatus.UNMATCHED.getCode();
            bound = null;
        } else {
            bound = metadata.getOwnerId() == null
                    ? metadataV2Support.upsertByOwner(MediaMetadataOwnerType.MOVIE.getCode(), movie.getId(), metadata)
                    : metadata;
            metadataId = bound.getId();
            matchStatus = MediaMatchStatus.MATCHED.getCode();
        }
        mediaMovieMapper.update(null, new LambdaUpdateWrapper<MediaMovie>()
                .eq(MediaMovie::getId, movie.getId())
                .set(MediaMovie::getMetadataId, metadataId)
                .set(MediaMovie::getMatchStatus, matchStatus));
        movie.setMetadataId(metadataId);
        movie.setMatchStatus(matchStatus);
        return bound;
    }

    // ---------- 电视削刮 ----------

    private boolean scrapeSeries(MediaDirectory directory, boolean force) {
        boolean partial = false;
        List<MediaSeriesV2> seriesList = mediaSeriesV2Mapper.selectList(new LambdaQueryWrapper<MediaSeriesV2>()
                .eq(MediaSeriesV2::getDirectoryId, directory.getId()));
        for (MediaSeriesV2 series : seriesList) {
            if (mediaTaskSupport.isCancelled(directory.getId())) {
                return partial;
            }
            if (!needScrape(series.getMatchStatus(), series.getMetadataComplete(), force)) {
                continue;
            }
            try {
                // 本地优先：剧文件夹存在 tvshow.nfo/本地图片时完全信任本地内容，不请求 TMDB
                MediaMetadataV2 localMetadata = mediaTvScrapeSupport.scrapeSeriesLocalNfo(series);
                if (localMetadata != null) {
                    mediaTvScrapeSupport.applyLocalSeriesMatch(series, localMetadata);
                    continue;
                }
                MediaMetadataV2 metadata = tmdbService.autoMatchV2(directory.getUserId(), MediaType.TV.getCode(),
                        series.getSeriesName(), series.getReleaseYear());
                if (metadata == null) {
                    mediaTvScrapeSupport.applySeriesUnmatch(series);
                    continue;
                }
                mediaTvScrapeSupport.applySeriesMatchWithDerivation(series, metadata, MediaMatchStatus.MATCHED.getCode());
            } catch (Exception e) {
                log.warn("剧集削刮失败: series={}, error={}", series.getSeriesName(), e.getMessage());
                partial = true;
            }
        }
        return partial;
    }

    // ---------- 通用 ----------

    /**
     * 是否需要削刮：manual 永不覆盖；force 处理全部非 manual，否则只处理「未匹配或不完整」。
     */
    private boolean needScrape(String matchStatus, Boolean metadataComplete, boolean force) {
        if (MediaMatchStatus.MANUAL.getCode().equals(matchStatus)) {
            return false;
        }
        if (force) {
            return true;
        }
        return MediaMatchStatus.UNMATCHED.getCode().equals(matchStatus) || !Boolean.TRUE.equals(metadataComplete);
    }

    private MediaNfoSupport.NfoData readNfo(FileNode nfoNode) {
        byte[] bytes = persistSupport.readFileBytes(nfoNode);
        return bytes == null ? null : mediaNfoSupport.parse(new String(bytes, StandardCharsets.UTF_8));
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
