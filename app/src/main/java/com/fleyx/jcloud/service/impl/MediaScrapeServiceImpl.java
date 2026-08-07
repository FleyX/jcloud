package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.common.enums.MediaScrapeStatus;
import com.fleyx.jcloud.common.enums.MediaType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.service.MediaScrapeService;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.MediaArtworkPersistSupport;
import com.fleyx.jcloud.service.support.MediaArtworkPersistV2Support;
import com.fleyx.jcloud.service.support.MediaMetadataCompleteSupport;
import com.fleyx.jcloud.service.support.MediaMetadataSupport;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 媒体库削刮服务实现（新模型，ADR 0021 / issue #20）。
 * <p>
 * 削刮为本地优先（ADR 0023）：视频同目录存在 NFO 或本地媒体图片时本地字段优先、缺失字段由 TMDB 补全
 * （有 tmdbId 按 ID 拉详情合并，仅本地图片无 NFO 时经自动匹配补文本），合并结果整体写回 NFO；
 * 本地缺失时电影以电影行为单位（先文件名解析结果，失败用电影文件夹名兜底）、
 * 电视以剧为单位（一次匹配应用到全剧，季/集元数据按剧级匹配派生，见 {@link MediaTvScrapeSupport}）。
 * 削刮成功后立即把元数据写回视频目录的 NFO 与图片，落盘失败不影响元数据入库。
 * 处理范围为「未匹配、不完整或 NFO 缺失」的非 manual 行（force 时全部非 manual 行）；
 * manual 永不覆盖匹配，但 manual/已匹配行 NFO 或背景图产物缺失时复用已有元数据行补回
 * （不重新匹配、不改绑定，工单 03/05/07），完整性翻 false 的 manual 行同样补回；
 * force=true 时非 manual 行先入 force 通道：有既有元数据且 tmdbId 非空按 ID 重新拉详情、
 * 否则自动匹配，TMDB 全量覆盖字段并全量替换图片/NFO 产物；拉取/匹配失败回退本地优先主流程
 * （退化为非强制 persist），不清空既有匹配（工单 06/07）。
 * 单条刷新（{@link #refreshItem}）分两模式：missing 补齐缺失文本字段并校验产物缺失则重建；
 * force 重新拉取 TMDB 全量覆盖（manual 行不重新匹配、不覆盖字段，仅按 force 语义全量替换产物）。
 * 每次削刮结束后重算电影/剧集行的元数据完整性（{@link MediaMetadataCompleteSupport}）。
 * 与扫描按媒体库互斥（{@link MediaTaskSupport}），协作式取消。
 */
@Slf4j
@Service
public class MediaScrapeServiceImpl implements MediaScrapeService {

    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final MediaMovieMapper mediaMovieMapper;
    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final MediaMovieFileMapper mediaMovieFileMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaEpisodeFileMapper mediaEpisodeFileMapper;
    private final FileMapper fileMapper;
    private final TmdbService tmdbService;
    private final MediaTaskSupport mediaTaskSupport;
    private final MediaNfoSupport mediaNfoSupport;
    private final MediaMetadataSupport metadataV2Support;
    private final MediaMetadataCompleteSupport completeSupport;
    private final MediaTvScrapeSupport mediaTvScrapeSupport;
    private final MediaArtworkPersistSupport persistSupport;
    private final MediaArtworkPersistV2Support artworkPersistV2Support;
    private final MediaPlaybackResolveSupport playbackResolveSupport;
    private final ObjectMapper objectMapper;
    private final TaskExecutor taskExecutor;

    public MediaScrapeServiceImpl(MediaDirectoryMapper mediaDirectoryMapper, MediaMovieMapper mediaMovieMapper,
                                  MediaSeriesMapper mediaSeriesMapper, MediaMetadataMapper mediaMetadataMapper,
                                  MediaMovieFileMapper mediaMovieFileMapper, MediaEpisodeMapper mediaEpisodeMapper,
                                  MediaEpisodeFileMapper mediaEpisodeFileMapper, FileMapper fileMapper,
                                  TmdbService tmdbService, MediaTaskSupport mediaTaskSupport,
                                  MediaNfoSupport mediaNfoSupport, MediaMetadataSupport metadataV2Support,
                                  MediaMetadataCompleteSupport completeSupport,
                                  MediaTvScrapeSupport mediaTvScrapeSupport,
                                  MediaArtworkPersistSupport persistSupport,
                                  MediaArtworkPersistV2Support artworkPersistV2Support,
                                  MediaPlaybackResolveSupport playbackResolveSupport,
                                  ObjectMapper objectMapper,
                                  @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.mediaDirectoryMapper = mediaDirectoryMapper;
        this.mediaMovieMapper = mediaMovieMapper;
        this.mediaSeriesMapper = mediaSeriesMapper;
        this.mediaMetadataMapper = mediaMetadataMapper;
        this.mediaMovieFileMapper = mediaMovieFileMapper;
        this.mediaEpisodeMapper = mediaEpisodeMapper;
        this.mediaEpisodeFileMapper = mediaEpisodeFileMapper;
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
        this.objectMapper = objectMapper;
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
    public void refreshItem(String metadataId, String userId, String mode) {
        MediaMetadata metadata = mediaMetadataMapper.selectById(metadataId);
        if (metadata == null || !userId.equals(metadata.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "元数据不存在");
        }
        MediaMetadataOwnerType ownerType = MediaMetadataOwnerType.of(metadata.getOwnerType());
        if (ownerType == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "未知元数据归属类型");
        }
        switch (ownerType) {
            case MOVIE -> refreshMovieItem(metadata, userId, mode);
            case SERIES -> refreshSeriesItem(metadata, userId, mode);
            default -> throw new BusinessException(ResultCode.BUSINESS_ERROR, "请刷新所属剧集");
        }
    }

    /**
     * 单条刷新电影（工单 06/07）：missing 模式已匹配行复用元数据行——文本缺失由 TMDB 补全（已完整行
     * 方法内短路不拉网络）、已有字段不动，产物经 persist IfMissing 校验缺失重建；manual 行只做产物补回
     * 不改字段；未匹配行走完整非强制削刮。force 模式非 manual 行走整库 force 同路径（TMDB 全量覆盖，
     * 失败回退本地优先不清空匹配）；manual 行豁免字段覆盖，复用既有元数据行按 force 语义全量替换产物。
     */
    private void refreshMovieItem(MediaMetadata metadata, String userId, String mode) {
        MediaMovie movie = mediaMovieMapper.selectById(metadata.getOwnerId());
        if (movie == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "电影不存在");
        }
        if ("force".equals(mode)) {
            if (MediaMatchStatus.MANUAL.getCode().equals(movie.getMatchStatus())) {
                refillMovieArtifacts(movie, true);
                return;
            }
            MediaDirectory directory = mediaDirectoryMapper.selectById(movie.getDirectoryId());
            if (directory == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "媒体库不存在");
            }
            scrapeMovie(directory, movie, true);
            return;
        }
        if (MediaMatchStatus.MANUAL.getCode().equals(movie.getMatchStatus())) {
            refillMovieArtifacts(movie, false);
            return;
        }
        if (MediaMatchStatus.MATCHED.getCode().equals(movie.getMatchStatus()) && movie.getMetadataId() != null) {
            MediaMetadata existing = mediaMetadataMapper.selectById(movie.getMetadataId());
            if (existing != null) {
                MediaMetadata enriched = enrichLocalWithTmdb(existing, userId, MediaType.MOVIE.getCode());
                MediaMetadata bound = applyMovieMatch(movie, enriched);
                if (bound != null) {
                    artworkPersistV2Support.persistMovieV2(movie, bound, false);
                }
            }
            completeSupport.refreshMovieComplete(movie);
            return;
        }
        MediaDirectory directory = mediaDirectoryMapper.selectById(movie.getDirectoryId());
        if (directory != null) {
            scrapeMovie(directory, movie, false);
        }
    }

    /**
     * 单条刷新剧集（工单 06/07）：missing 模式已匹配行复用剧级元数据行——文本缺失由 TMDB 补全（已完整行
     * 短路不拉网络）、已有字段不动，经派生季/集（非强制合并）与 persist IfMissing 重建缺失产物；
     * manual 行只做产物补回不改字段；未匹配行走完整非强制削刮。force 模式非 manual 行走整库 force 同路径
     * （剧级详情全量覆盖，失败回退本地优先不清空匹配）；manual 行豁免字段覆盖，复用既有元数据行按 force
     * 语义全量替换产物。
     */
    private void refreshSeriesItem(MediaMetadata metadata, String userId, String mode) {
        MediaSeries series = mediaSeriesMapper.selectById(metadata.getOwnerId());
        if (series == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "电视剧不存在");
        }
        if ("force".equals(mode)) {
            if (MediaMatchStatus.MANUAL.getCode().equals(series.getMatchStatus())) {
                refillSeriesArtifacts(series, true);
                return;
            }
            MediaDirectory directory = mediaDirectoryMapper.selectById(series.getDirectoryId());
            if (directory == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "媒体库不存在");
            }
            scrapeSeriesItem(directory, series, true);
            return;
        }
        if (MediaMatchStatus.MANUAL.getCode().equals(series.getMatchStatus())) {
            refillSeriesArtifacts(series, false);
            return;
        }
        if (MediaMatchStatus.MATCHED.getCode().equals(series.getMatchStatus()) && series.getMetadataId() != null) {
            MediaMetadata existing = mediaMetadataMapper.selectById(series.getMetadataId());
            if (existing != null) {
                MediaMetadata enriched = enrichLocalWithTmdb(existing, userId, MediaType.TV.getCode());
                mediaTvScrapeSupport.applySeriesMatchWithDerivation(series, enriched,
                        MediaMatchStatus.MATCHED.getCode(), false);
            } else {
                completeSupport.refreshSeriesComplete(series);
            }
            return;
        }
        MediaDirectory directory = mediaDirectoryMapper.selectById(series.getDirectoryId());
        if (directory != null) {
            scrapeSeriesItem(directory, series, false);
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
        Map<String, Boolean> nfoMissingByMovie = collectMovieNfoMissing(movies);
        Map<String, Boolean> backdropMissingByMovie = collectMovieBackdropMissing(movies);
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
                    refillMovieArtifacts(movie, false);
                }
                continue;
            }
            try {
                scrapeMovie(directory, movie, force);
            } catch (Exception e) {
                log.warn("电影削刮失败: movie={}, error={}", movie.getId(), e.getMessage());
                partial = true;
            }
        }
        return partial;
    }

    /**
     * 单部电影削刮：本地优先（movie.nfo 优先、同名 .nfo 兜底 / poster / fanart），本地内容存在时本地字段优先、
     * 缺失字段由 TMDB 补全（有 tmdbId 按 ID 拉详情，仅本地图片无 NFO 时经自动匹配补文本）；无本地内容时 TMDB
     * 先文件名解析结果、失败用电影文件夹名兜底；已匹配行无本地内容时复用已有元数据行补产物，不重新搜索。
     * force=true 时先入 force 通道（工单 06/07）：有既有元数据且 tmdbId 非空按 ID 重新拉详情、否则自动匹配，
     * 匹配成功则全量覆盖字段并全量替换图片/NFO 产物；拉取/匹配失败不打断、不 unmatch，回退本地优先主流程
     * （force 自动退化为非强制 persist）。
     */
    private void scrapeMovie(MediaDirectory directory, MediaMovie movie, boolean force) {
        if (force) {
            MediaMetadata pulled = pullTmdbForForce(directory, movie);
            if (pulled != null) {
                MediaMetadata bound = applyMovieMatch(movie, pulled);
                if (bound != null) {
                    artworkPersistV2Support.persistMovieV2(movie, bound, true);
                }
                completeSupport.refreshMovieComplete(movie);
                return;
            }
            log.warn("电影强制削刮 TMDB 拉取失败，回退本地优先流程: movie={}", movie.getId());
        }
        scrapeMovieLocalFirst(directory, movie);
    }

    /**
     * 电影本地优先主流程（非强制 persist）：本地优先削刮 + TMDB 补全；force 通道失败后同样回退到此
     * （退化为非强制，不清空既有匹配，工单 07）。
     */
    private void scrapeMovieLocalFirst(MediaDirectory directory, MediaMovie movie) {
        MediaMetadata local = scrapeMovieLocalNfo(movie);
        MediaMetadata metadata = local;
        if (metadata != null && metadata.getTmdbId() != null) {
            metadata = enrichLocalWithTmdb(metadata, directory.getUserId(), MediaType.MOVIE.getCode());
        }
        if (metadata == null) {
            if (MediaMatchStatus.MATCHED.getCode().equals(movie.getMatchStatus())
                    && movie.getMetadataId() != null) {
                metadata = mediaMetadataMapper.selectById(movie.getMetadataId());
            }
            if (metadata == null) {
                metadata = matchMovieByFile(directory, movie);
            }
        } else if (metadata.getTmdbId() == null) {
            // 本地仅图片无 NFO 或 NFO 未含 tmdbid：文本字段由 TMDB 自动匹配补全，图片沿用本地
            MediaMetadata matched = matchMovieByFile(directory, movie);
            if (matched != null) {
                metadata = metadataV2Support.mergeLocalWithTmdb(metadata, matched);
            }
        }
        MediaMetadata bound = applyMovieMatch(movie, metadata);
        if (bound != null) {
            artworkPersistV2Support.persistMovieV2(movie, bound, false);
        }
        completeSupport.refreshMovieComplete(movie);
    }

    /**
     * 电影 force 通道（工单 06/07）：有既有元数据且 tmdbId 非空按 ID 重新拉取详情，否则经文件名/文件夹名
     * 自动匹配；详情拉取异常视为失败（不打断削刮）。
     *
     * @return 全量 TMDB 元数据；拉取/匹配失败返回 null（调用方回退本地优先主流程）
     */
    private MediaMetadata pullTmdbForForce(MediaDirectory directory, MediaMovie movie) {
        MediaMetadata existing = movie.getMetadataId() == null ? null
                : mediaMetadataMapper.selectById(movie.getMetadataId());
        if (existing != null && existing.getTmdbId() != null) {
            try {
                return tmdbService.fetchDetailV2(directory.getUserId(), existing.getTmdbId(),
                        MediaType.MOVIE.getCode());
            } catch (Exception e) {
                log.warn("电影强制削刮拉取详情失败，回退本地优先: movie={}, tmdbId={}, error={}",
                        movie.getId(), existing.getTmdbId(), e.getMessage());
                return null;
            }
        }
        return matchMovieByFile(directory, movie);
    }

    /**
     * TMDB 自动匹配电影：先按视频文件名解析结果匹配，失败用电影文件夹名兜底
     * （扫描已把文件夹名清理为 movie.title）。
     */
    private MediaMetadata matchMovieByFile(MediaDirectory directory, MediaMovie movie) {
        MediaMovieFile file = playbackResolveSupport.pickMovieFile(movie);
        FileNode video = file == null ? null : fileMapper.selectById(file.getFileNodeId());
        if (video == null) {
            return null;
        }
        MediaFileNameParser.ParseResult parsed = MediaFileNameParser.parse(video.getName(), null, null);
        MediaMetadata metadata = tmdbService.autoMatchV2(directory.getUserId(), MediaType.MOVIE.getCode(),
                parsed.title(), parsed.year());
        String fallback = movie.getTitle();
        if (metadata == null && !fallback.isBlank() && !fallback.equals(parsed.title())) {
            metadata = tmdbService.autoMatchV2(directory.getUserId(), MediaType.MOVIE.getCode(),
                    fallback, parsed.year());
        }
        return metadata;
    }

    /**
     * 电影本地优先削刮：优先读取电影文件夹 {@code movie.nfo}，不存在时回退与视频同名的 {@code .nfo}，
     * 本地字段优先、缺失字段由调用方经 {@link #enrichLocalWithTmdb} 用 TMDB 补全（ADR 0023）；
     * 无 NFO 但存在本地图片（按海报/背景识别链，ADR 0022）时同样本地优先，构建缺失文本字段的
     * local_nfo 元数据（标记不完整），图片绑定本地文件。
     *
     * @return 已绑定 owner 的本地元数据，无 NFO 且无本地图片时返回 null
     */
    private MediaMetadata scrapeMovieLocalNfo(MediaMovie movie) {
        FileNode folder = fileMapper.selectById(movie.getFolderNodeId());
        MediaMovieFile file = playbackResolveSupport.pickMovieFile(movie);
        FileNode video = file == null ? null : fileMapper.selectById(file.getFileNodeId());
        if (folder == null || video == null) {
            return null;
        }
        String userId = movie.getUserId();
        FileNode nfoNode = persistSupport.findFirstChildFile(userId, folder.getId(),
                List.of(MediaNfoSupport.MOVIE_NFO, mediaNfoSupport.nfoNameOf(video.getName())));
        MediaNfoSupport.NfoData data = nfoNode == null ? null : readNfo(nfoNode);
        FileNode poster = persistSupport.findFirstChildFile(userId, folder.getId(), MediaNfoSupport.MOVIE_POSTER_NAMES);
        FileNode fanart = persistSupport.findFirstChildFile(userId, folder.getId(), MediaNfoSupport.BACKDROP_NAMES);
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
    private MediaMetadata applyMovieMatch(MediaMovie movie, MediaMetadata metadata) {
        String metadataId;
        String matchStatus;
        MediaMetadata bound;
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

    /**
     * 已匹配（含 manual）行的图片/NFO 产物补回（工单 03/05/07）：复用已有元数据行的 rawJson 重建缺失产物，
     * 不触碰 matchStatus/metadataId、不重新匹配。无元数据行直接返回；local_nfo 来源同样经
     * persistMovieV2 整体写回（其补全由削刮主路径完成，工单 05）。
     * force=true 时（manual 单条强制刷新，工单 07）按 force 语义全量替换图片/NFO 产物，同样不改字段。
     */
    private void refillMovieArtifacts(MediaMovie movie, boolean force) {
        if (movie.getMetadataId() == null) {
            return;
        }
        MediaMetadata metadata = mediaMetadataMapper.selectById(movie.getMetadataId());
        if (metadata == null) {
            return;
        }
        artworkPersistV2Support.persistMovieV2(movie, metadata, force);
        completeSupport.refreshMovieComplete(movie);
    }

    // ---------- 电视削刮 ----------

    private boolean scrapeSeries(MediaDirectory directory, boolean force) {
        boolean partial = false;
        List<MediaSeries> seriesList = mediaSeriesMapper.selectList(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getDirectoryId, directory.getId()));
        Map<String, Boolean> nfoMissingBySeries = collectSeriesNfoMissing(seriesList);
        Map<String, Boolean> backdropMissingBySeries = collectSeriesBackdropMissing(seriesList);
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
                    refillSeriesArtifacts(series, false);
                }
                continue;
            }
            try {
                scrapeSeriesItem(directory, series, force);
            } catch (Exception e) {
                log.warn("剧集削刮失败: series={}, error={}", series.getSeriesName(), e.getMessage());
                partial = true;
            }
        }
        return partial;
    }

    /**
     * 单部剧集削刮：force=true 时先入 force 通道（工单 06/07）：有既有剧级元数据且 tmdbId 非空按 ID
     * 重新拉详情、否则按剧名自动匹配，成功则派生季/集（全量覆盖）并全量替换写回；拉取/匹配失败不打断、
     * 不 unmatch，回退本地优先主流程（退化为非强制 persist）。否则本地优先（tvshow.nfo/本地图片）字段优先、
     * 缺失字段由 TMDB 补全；无本地内容时已匹配行复用已有剧级元数据行补产物（派生季/集，工单 03），
     * 未匹配行按剧名自动匹配。
     */
    private void scrapeSeriesItem(MediaDirectory directory, MediaSeries series, boolean force) {
        if (force) {
            MediaMetadata pulled = pullTmdbForForce(directory, series);
            if (pulled != null) {
                mediaTvScrapeSupport.applySeriesMatchWithDerivation(series, pulled,
                        MediaMatchStatus.MATCHED.getCode(), true);
                return;
            }
            log.warn("剧集强制削刮 TMDB 拉取失败，回退本地优先流程: series={}", series.getSeriesName());
        }
        scrapeSeriesLocalFirst(directory, series);
    }

    /**
     * 剧集本地优先主流程（非强制 persist）：本地优先削刮 + TMDB 补全；force 通道失败后同样回退到此
     * （退化为非强制，不清空既有匹配，工单 07）。
     */
    private void scrapeSeriesLocalFirst(MediaDirectory directory, MediaSeries series) {
        // 本地优先：剧文件夹存在 tvshow.nfo/本地图片时本地字段优先，缺失字段由 TMDB 补全
        MediaMetadata localMetadata = mediaTvScrapeSupport.scrapeSeriesLocalNfo(series);
        if (localMetadata != null) {
            localMetadata = enrichLocalSeries(localMetadata, directory.getUserId(), series);
            mediaTvScrapeSupport.applyLocalSeriesMatch(series, localMetadata);
            return;
        }
        // 已匹配行无本地内容时复用已有剧级元数据行补产物（派生季/集），不重新搜索（工单 03）
        MediaMetadata metadata = MediaMatchStatus.MATCHED.getCode().equals(series.getMatchStatus())
                && series.getMetadataId() != null ? mediaMetadataMapper.selectById(series.getMetadataId()) : null;
        if (metadata == null) {
            metadata = tmdbService.autoMatchV2(directory.getUserId(), MediaType.TV.getCode(),
                    series.getSeriesName(), series.getReleaseYear());
            if (metadata == null) {
                mediaTvScrapeSupport.applySeriesUnmatch(series);
                return;
            }
        }
        mediaTvScrapeSupport.applySeriesMatchWithDerivation(series, metadata,
                MediaMatchStatus.MATCHED.getCode(), false);
    }

    /**
     * 剧集 force 通道（工单 06/07）：有既有剧级元数据且 tmdbId 非空按 ID 重新拉取剧级详情，拉取失败
     * （返回 null 或抛异常）回退按剧名自动匹配；详情拉取异常视为失败（不打断削刮）。
     *
     * @return 全量 TMDB 剧级元数据；拉取/匹配均失败返回 null（调用方回退本地优先主流程）
     */
    private MediaMetadata pullTmdbForForce(MediaDirectory directory, MediaSeries series) {
        MediaMetadata existing = series.getMetadataId() == null ? null
                : mediaMetadataMapper.selectById(series.getMetadataId());
        MediaMetadata metadata = null;
        if (existing != null && existing.getTmdbId() != null) {
            try {
                metadata = tmdbService.fetchDetailV2(directory.getUserId(), existing.getTmdbId(),
                        MediaType.TV.getCode());
            } catch (Exception e) {
                log.warn("剧集强制削刮拉取详情失败，回退按剧名自动匹配: series={}, tmdbId={}, error={}",
                        series.getSeriesName(), existing.getTmdbId(), e.getMessage());
            }
        }
        return metadata != null ? metadata : tmdbService.autoMatchV2(directory.getUserId(), MediaType.TV.getCode(),
                series.getSeriesName(), series.getReleaseYear());
    }

    /**
     * 剧集本地元数据 TMDB 补全（ADR 0023）：有 tmdbId 按 ID 拉详情合并（本地字段优先、缺失字段补齐、
     * rawJson 恒取远端）；无 tmdbId（仅本地图片 / tvshow.nfo 未含 tmdbid）时经自动匹配补文本，
     * 匹配失败维持现状。图片绑定始终沿用本地文件。
     */
    private MediaMetadata enrichLocalSeries(MediaMetadata local, String userId, MediaSeries series) {
        if (local == null) {
            return null;
        }
        if (local.getTmdbId() != null) {
            return enrichLocalWithTmdb(local, userId, MediaType.TV.getCode());
        }
        MediaMetadata matched;
        try {
            matched = tmdbService.autoMatchV2(userId, MediaType.TV.getCode(),
                    series.getSeriesName(), series.getReleaseYear());
        } catch (Exception e) {
            log.warn("剧集本地元数据自动匹配补全失败，维持本地: series={}, error={}",
                    series.getSeriesName(), e.getMessage());
            return local;
        }
        if (matched == null) {
            return local;
        }
        return metadataV2Support.mergeLocalWithTmdb(local, matched);
    }

    /**
     * 已匹配（含 manual）剧集的图片/NFO 产物补回（工单 03/05/07）：复用已有剧级元数据行重建缺失产物
     * （剧海报/背景、季海报、集剧照与各级 NFO），不触碰 matchStatus/metadataId、不重新匹配。
     * 无元数据行直接返回；local_nfo 来源同样经 persistSeriesV2 整体写回（其补全由削刮主路径完成，工单 05）。
     * force=true 时（manual 单条强制刷新，工单 07）按 force 语义全量替换产物，同样不改字段。
     */
    private void refillSeriesArtifacts(MediaSeries series, boolean force) {
        if (series.getMetadataId() == null) {
            return;
        }
        MediaMetadata metadata = mediaMetadataMapper.selectById(series.getMetadataId());
        if (metadata == null) {
            return;
        }
        artworkPersistV2Support.persistSeriesV2(series, metadata, force);
        completeSupport.refreshSeriesComplete(series);
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

    /**
     * 本地元数据 TMDB 补全（ADR 0023 合并语义）：local 为 null、tmdbId 为空或本地已完整（5 项齐备）
     * 时原样返回（方法内短路，仅不完整且 tmdbId 非空时实际需要网络）；
     * 否则 {@code fetchDetailV2} 拉详情逐字段合并——本地非空字段优先、缺失字段用远端值补齐，
     * rawJson 恒取远端（图片写回需要）；远端拉取失败原样返回（不阻断削刮）。
     */
    private MediaMetadata enrichLocalWithTmdb(MediaMetadata local, String userId, String mediaType) {
        if (local == null || local.getTmdbId() == null || completeSupport.isComplete(local)) {
            return local;
        }
        MediaMetadata remote;
        try {
            remote = tmdbService.fetchDetailV2(userId, local.getTmdbId(), mediaType);
        } catch (Exception e) {
            log.warn("本地元数据 TMDB 补全失败，维持本地: tmdbId={}, error={}", local.getTmdbId(), e.getMessage());
            return local;
        }
        if (remote == null) {
            return local;
        }
        return metadataV2Support.mergeLocalWithTmdb(local, remote);
    }

    /**
     * 批量收集各电影 NFO 是否缺失（一次 IN parent_id 查询）：电影文件夹下 {@code movie.nfo}
     * 与视频同名 {@code .nfo} 均不存在才算缺失（识别链两个名字都没有）。
     *
     * @return movieId → nfoMissing
     */
    private Map<String, Boolean> collectMovieNfoMissing(List<MediaMovie> movies) {
        if (movies.isEmpty()) {
            return Map.of();
        }
        List<String> movieIds = movies.stream().map(MediaMovie::getId).toList();
        Map<String, String> fileNodeIdByMovie = mediaMovieFileMapper.selectList(
                        new LambdaQueryWrapper<MediaMovieFile>().in(MediaMovieFile::getMovieId, movieIds))
                .stream().collect(Collectors.toMap(MediaMovieFile::getMovieId, MediaMovieFile::getFileNodeId, (a, b) -> a));
        Map<String, FileNode> fileById = loadFileById(fileNodeIdByMovie.values());
        List<String> parentIds = new java.util.ArrayList<>(movies.stream()
                .map(MediaMovie::getFolderNodeId).filter(Objects::nonNull).collect(Collectors.toSet()));
        fileById.values().stream().map(FileNode::getParentId).filter(Objects::nonNull).forEach(parentIds::add);
        Map<String, Set<String>> namesByParent = loadNamesByParent(parentIds);
        Map<String, Boolean> result = new HashMap<>();
        for (MediaMovie movie : movies) {
            Set<String> folderNames = namesByParent.getOrDefault(movie.getFolderNodeId(), Set.of());
            FileNode video = fileById.get(fileNodeIdByMovie.get(movie.getId()));
            String videoName = video == null ? null : video.getName();
            boolean hasNfo = folderNames.contains(MediaNfoSupport.MOVIE_NFO)
                    || (videoName != null && folderNames.contains(mediaNfoSupport.nfoNameOf(videoName)));
            result.put(movie.getId(), !hasNfo);
        }
        return result;
    }

    /**
     * 批量收集各剧集 NFO 是否缺失（一次 IN parent_id 查询）：剧文件夹无 {@code tvshow.nfo}，
     * 或任一集视频同目录无同名 {@code .nfo}（聚合到剧行）。
     *
     * @return seriesId → nfoMissing
     */
    private Map<String, Boolean> collectSeriesNfoMissing(List<MediaSeries> seriesList) {
        if (seriesList.isEmpty()) {
            return Map.of();
        }
        List<String> seriesIds = seriesList.stream().map(MediaSeries::getId).toList();
        List<MediaEpisode> allEpisodes = mediaEpisodeMapper.selectList(
                new LambdaQueryWrapper<MediaEpisode>().in(MediaEpisode::getSeriesId, seriesIds));
        List<String> episodeIds = allEpisodes.stream().map(MediaEpisode::getId).toList();
        Map<String, String> fileNodeIdByEpisode = episodeIds.isEmpty() ? Map.of()
                : mediaEpisodeFileMapper.selectList(
                                new LambdaQueryWrapper<MediaEpisodeFile>().in(MediaEpisodeFile::getEpisodeId, episodeIds))
                        .stream().collect(Collectors.toMap(MediaEpisodeFile::getEpisodeId, MediaEpisodeFile::getFileNodeId, (a, b) -> a));
        Map<String, FileNode> fileById = loadFileById(fileNodeIdByEpisode.values());
        List<String> parentIds = new java.util.ArrayList<>(seriesList.stream()
                .map(MediaSeries::getFolderNodeId).filter(Objects::nonNull).collect(Collectors.toSet()));
        fileById.values().stream().map(FileNode::getParentId).filter(Objects::nonNull).forEach(parentIds::add);
        Map<String, Set<String>> namesByParent = loadNamesByParent(parentIds);
        Map<String, Boolean> result = new HashMap<>();
        for (MediaSeries series : seriesList) {
            Set<String> folderNames = namesByParent.getOrDefault(series.getFolderNodeId(), Set.of());
            boolean hasTvshowNfo = folderNames.contains(MediaNfoSupport.TVSHOW_NFO);
            boolean allEpisodesHaveNfo = true;
            for (MediaEpisode episode : allEpisodes) {
                if (!series.getId().equals(episode.getSeriesId())) {
                    continue;
                }
                FileNode video = fileById.get(fileNodeIdByEpisode.get(episode.getId()));
                if (video == null) {
                    continue;
                }
                if (!namesByParent.getOrDefault(video.getParentId(), Set.of())
                        .contains(mediaNfoSupport.nfoNameOf(video.getName()))) {
                    allEpisodesHaveNfo = false;
                    break;
                }
            }
            result.put(series.getId(), !hasTvshowNfo || !allEpisodesHaveNfo);
        }
        return result;
    }

    /**
     * 批量收集各电影背景图产物是否缺失（工单 07）：按 metadataId 一次 selectBatchIds 加载元数据行，
     * 非空 backdropFileNodeId 合并做一次 IN 查询得存活集合；缺失判定见 {@link #backdropMissing}。
     * 无元数据行的电影不判缺失（本就在削刮候选里）。
     *
     * @return movieId → backdropMissing
     */
    private Map<String, Boolean> collectMovieBackdropMissing(List<MediaMovie> movies) {
        if (movies.isEmpty()) {
            return Map.of();
        }
        Map<String, MediaMetadata> metadataById = loadMetadataById(movies.stream()
                .map(MediaMovie::getMetadataId).filter(Objects::nonNull).distinct().toList());
        Set<String> aliveNodeIds = loadFileById(metadataById.values().stream()
                .map(MediaMetadata::getBackdropFileNodeId).filter(Objects::nonNull).toList()).keySet();
        Map<String, Boolean> result = new HashMap<>();
        for (MediaMovie movie : movies) {
            result.put(movie.getId(), backdropMissing(
                    movie.getMetadataId() == null ? null : metadataById.get(movie.getMetadataId()), aliveNodeIds));
        }
        return result;
    }

    /**
     * 批量收集各剧集背景图产物是否缺失（工单 07）：只看剧级元数据行（季海报/集剧照指针悬空已由
     * 完整性聚合覆盖，不重复探测），判定逻辑与电影版一致。
     *
     * @return seriesId → backdropMissing
     */
    private Map<String, Boolean> collectSeriesBackdropMissing(List<MediaSeries> seriesList) {
        if (seriesList.isEmpty()) {
            return Map.of();
        }
        Map<String, MediaMetadata> metadataById = loadMetadataById(seriesList.stream()
                .map(MediaSeries::getMetadataId).filter(Objects::nonNull).distinct().toList());
        Set<String> aliveNodeIds = loadFileById(metadataById.values().stream()
                .map(MediaMetadata::getBackdropFileNodeId).filter(Objects::nonNull).toList()).keySet();
        Map<String, Boolean> result = new HashMap<>();
        for (MediaSeries series : seriesList) {
            result.put(series.getId(), backdropMissing(
                    series.getMetadataId() == null ? null : metadataById.get(series.getMetadataId()), aliveNodeIds));
        }
        return result;
    }

    /**
     * 背景图缺失判定：指针非空且文件已不存在，或指针为空但 rawJson 含非空 backdrop_path。
     * 无元数据行视为不缺失（行本就在削刮候选里）。
     */
    private boolean backdropMissing(MediaMetadata metadata, Set<String> aliveNodeIds) {
        if (metadata == null) {
            return false;
        }
        if (metadata.getBackdropFileNodeId() != null) {
            return !aliveNodeIds.contains(metadata.getBackdropFileNodeId());
        }
        return backdropPathPresent(metadata.getRawJson());
    }

    /** 一次 selectBatchIds 加载元数据行（id → 行），入参已去空去重。 */
    private Map<String, MediaMetadata> loadMetadataById(List<String> metadataIds) {
        if (metadataIds.isEmpty()) {
            return Map.of();
        }
        return mediaMetadataMapper.selectBatchIds(metadataIds).stream()
                .collect(Collectors.toMap(MediaMetadata::getId, Function.identity()));
    }

    /** rawJson 是否含非空 backdrop_path（与 persist 通道取图字段一致，解析失败视为无）。 */
    private boolean backdropPathPresent(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return false;
        }
        try {
            JsonNode value = objectMapper.readTree(rawJson).path("backdrop_path");
            return value.isTextual() && !value.asText().isBlank();
        } catch (Exception e) {
            log.warn("元数据原始响应解析失败: {}", e.getMessage());
            return false;
        }
    }

    /** 一次 IN 查询加载文件节点（id → 节点）。 */
    private Map<String, FileNode> loadFileById(java.util.Collection<String> fileNodeIds) {
        List<String> ids = fileNodeIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return fileMapper.selectList(new LambdaQueryWrapper<FileNode>().in(FileNode::getId, ids))
                .stream().collect(Collectors.toMap(FileNode::getId, Function.identity()));
    }

    /** 一次 IN parent_id 查询加载各目录下子文件名集合（parentId → 子文件名）。 */
    private Map<String, Set<String>> loadNamesByParent(List<String> parentIds) {
        List<String> ids = parentIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return fileMapper.selectList(new LambdaQueryWrapper<FileNode>().in(FileNode::getParentId, ids))
                .stream().collect(Collectors.groupingBy(FileNode::getParentId,
                        Collectors.mapping(FileNode::getName, Collectors.toSet())));
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
