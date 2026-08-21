package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.MediaMatchUpdateDto;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.dto.MediaProgressUpdateDto;
import com.fleyx.jcloud.model.dto.MediaWatchedUpdateDto;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.MediaGenreVo;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaSearchResultVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesVo;
import com.fleyx.jcloud.service.MediaItemService;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.MediaGenreSupport;
import com.fleyx.jcloud.service.support.MediaHomeItemSupport;
import com.fleyx.jcloud.service.support.MediaMovieQuerySupport;
import com.fleyx.jcloud.service.support.MediaMovieScrapeSupport;
import com.fleyx.jcloud.service.support.MediaOtherQuerySupport;
import com.fleyx.jcloud.service.support.MediaPlaybackResolveSupport;
import com.fleyx.jcloud.service.support.MediaTvQuerySupport;
import com.fleyx.jcloud.service.support.MediaTvScrapeSupport;
import com.fleyx.jcloud.service.support.MediaWatchedLinkageSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 媒体条目查询与匹配服务实现。
 * <p>
 * 电视（issue #17）/电影（issue #18）/其他（issue #19）海报墙与详情均已切换到新模型表，
 * 分别委托 {@link MediaTvQuerySupport} / {@link MediaMovieQuerySupport} / {@link MediaOtherQuerySupport}；
 * 播放进度记录到标题级新行（电影/集/其他），续播通过 last_play_file_id 定位版本文件。
 * 手动修正统一按行 ID（issue #21）：id 为电影行或剧集行 ID，集级手动修正已下线；
 * 旧表存量数据兜底分支已随旧四表弃用删除。
 */
@Service
@RequiredArgsConstructor
public class MediaItemServiceImpl implements MediaItemService {

    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaMovieMapper mediaMovieMapper;
    private final MediaMovieFileMapper mediaMovieFileMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaEpisodeFileMapper mediaEpisodeFileMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaOtherMapper mediaOtherMapper;
    private final TmdbService tmdbService;
    private final MediaTvQuerySupport mediaTvQuerySupport;
    private final MediaMovieQuerySupport mediaMovieQuerySupport;
    private final MediaOtherQuerySupport mediaOtherQuerySupport;
    private final MediaPlaybackResolveSupport mediaPlaybackResolveSupport;
    private final MediaTvScrapeSupport mediaTvScrapeSupport;
    private final MediaMovieScrapeSupport mediaMovieScrapeSupport;
    private final MediaGenreSupport mediaGenreSupport;
    private final MediaWatchedLinkageSupport mediaWatchedLinkageSupport;

    @Override
    public IPage<MediaItemVo> listMovies(String userId, MediaPageQueryDto query) {
        // 电影库新模型海报墙（issue #18）：按电影聚合，一部电影只出现一次
        return mediaMovieQuerySupport.listMovies(userId, query);
    }

    @Override
    public IPage<MediaSeriesVo> listSeries(String userId, MediaPageQueryDto query) {
        return mediaTvQuerySupport.listSeries(userId, query);
    }

    @Override
    public List<MediaItemVo> listEpisodes(String seriesId, String userId) {
        return mediaTvQuerySupport.listEpisodes(seriesId, userId);
    }

    @Override
    public IPage<MediaItemVo> listOthers(String userId, MediaPageQueryDto query) {
        // 其他库新模型网格列表（issue #19）：文件级一行一卡片
        return mediaOtherQuerySupport.listOthers(userId, query);
    }

    /**
     * 全局搜索每组条数上限。
     */
    private static final int SEARCH_SIZE_MAX = 50;

    @Override
    public MediaSearchResultVo search(String userId, String keyword, int size) {
        int pageSize = Math.max(1, Math.min(size, SEARCH_SIZE_MAX));
        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setPageNum(1L);
        query.setPageSize((long) pageSize);
        query.setKeyword(keyword);
        // 不传 directoryId 即跨库全局搜索（三条查询的 user_id 已是硬过滤），复用既有分页查询装配
        MediaSearchResultVo result = new MediaSearchResultVo();
        result.setMovies(listMovies(userId, query));
        result.setSeries(listSeries(userId, query));
        result.setOthers(listOthers(userId, query));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaItemVo updateMatch(String itemId, MediaMatchUpdateDto dto, String userId) {
        // 电影行手动修正（issue #20）
        MediaMovie movie = mediaMovieMapper.selectById(itemId);
        if (movie != null) {
            return applyMovieManualMatch(movie, dto, userId);
        }
        // 剧集行手动修正（issue #21：统一按行 ID，id = 剧集行 ID 时等效原系列级修正语义，整剧应用）
        MediaSeries series = mediaSeriesMapper.selectById(itemId);
        if (series != null) {
            return applySeriesManualMatch(series, dto, userId);
        }
        // 集级手动修正已下线（issue #15/#20）
        if (mediaEpisodeMapper.selectById(itemId) != null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "集级手动修正已下线");
        }
        throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
    }

    /**
     * 新模型电影手动修正：绑定元数据（owner=movie）→ 置 manual → 写回 → 重算完整性，
     * 编排委托 {@link MediaMovieScrapeSupport#applyMovieMatchWithPersist}。
     */
    private MediaItemVo applyMovieManualMatch(MediaMovie movie, MediaMatchUpdateDto dto, String userId) {
        if (!userId.equals(movie.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "电影不存在");
        }
        MediaMetadata detached = tmdbService.fetchDetailV2(userId, dto.getTmdbId(), dto.getMediaType());
        MediaMetadata bound = mediaMovieScrapeSupport.applyMovieMatchWithPersist(
                movie, detached, MediaMatchStatus.MANUAL.getCode(), false);
        MediaItemVo vo = new MediaItemVo();
        vo.setId(movie.getId());
        vo.setItemType(MediaItemType.MOVIE.getCode());
        vo.setMatchStatus(movie.getMatchStatus());
        vo.setMetadataId(movie.getMetadataId());
        vo.setTitle(bound.getTitle() == null ? movie.getTitle() : bound.getTitle());
        return vo;
    }

    /**
     * 新模型剧集行手动修正：整剧应用（绑定 owner=series → 置 manual → 派生季/集 → 写回 → 重算完整性）。
     */
    private MediaItemVo applySeriesManualMatch(MediaSeries series, MediaMatchUpdateDto dto, String userId) {
        if (!userId.equals(series.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "电视剧不存在");
        }
        MediaMetadata detached = tmdbService.fetchDetailV2(userId, dto.getTmdbId(), "tv");
        mediaTvScrapeSupport.applySeriesMatchWithDerivation(series, detached, MediaMatchStatus.MANUAL.getCode(), false);
        MediaItemVo vo = new MediaItemVo();
        vo.setId(series.getId());
        vo.setItemType("series");
        vo.setMatchStatus(series.getMatchStatus());
        vo.setMetadataId(series.getMetadataId());
        vo.setTitle(detached.getTitle() == null ? series.getSeriesName() : detached.getTitle());
        return vo;
    }

    @Override
    public void updateProgress(String itemId, MediaProgressUpdateDto dto, String userId) {
        // 播放进度记录到标题级新行（issue #19）：电影 → t_media_movie、集 → t_media_episode、
        // 其他 → t_media_other；一部电影多版本共享进度，续播按 last_play_file_id 定位版本文件。
        // 进度驱动已观看（工单 01）：进度达看完阈值自动置 watched 并清零进度，否则照写进度。
        LocalDateTime now = LocalDateTime.now();
        MediaMovie movie = mediaMovieMapper.selectById(itemId);
        if (movie != null) {
            if (!userId.equals(movie.getUserId())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
            }
            // 指定版本时校验该明细行属于此电影并记为该次播放版本，缺省按续播定位（last_play_file_id 优先）
            MediaMovieFile file = dto.getVersionId() == null
                    ? mediaPlaybackResolveSupport.pickMovieFile(movie)
                    : mediaPlaybackResolveSupport.pickVersion(movie, dto.getVersionId());
            movie.setLastPlayTime(now);
            if (file != null) {
                movie.setLastPlayFileId(file.getId());
            }
            applyWatchedDrivenProgress(movie.getWatched(), movie::setWatched, movie::setProgressMs,
                    dto.getProgressMs(), file == null ? null : file.getDurationMs());
            mediaMovieMapper.updateById(movie);
            return;
        }
        MediaEpisode episode = mediaEpisodeMapper.selectById(itemId);
        if (episode != null) {
            MediaSeries series = mediaSeriesMapper.selectById(episode.getSeriesId());
            if (series == null || !userId.equals(series.getUserId())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
            }
            MediaEpisodeFile file = mediaPlaybackResolveSupport.pickEpisodeFile(episode);
            episode.setLastPlayTime(now);
            if (file != null) {
                episode.setLastPlayFileId(file.getId());
            }
            boolean beforeWatched = Boolean.TRUE.equals(episode.getWatched());
            applyWatchedDrivenProgress(episode.getWatched(), episode::setWatched, episode::setProgressMs,
                    dto.getProgressMs(), file == null ? null : file.getDurationMs());
            boolean afterWatched = Boolean.TRUE.equals(episode.getWatched());
            mediaEpisodeMapper.updateById(episode);
            // 集由未看转已看（进度驱动）→ 重算父级联动（工单 02）
            if (!beforeWatched && afterWatched) {
                mediaWatchedLinkageSupport.recomputeParents(episode.getSeasonId(), episode.getSeriesId());
            }
            return;
        }
        MediaOther other = mediaOtherMapper.selectById(itemId);
        if (other == null || !userId.equals(other.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
        }
        other.setLastPlayTime(now);
        applyWatchedDrivenProgress(other.getWatched(), other::setWatched, other::setProgressMs,
                dto.getProgressMs(), other.getDurationMs());
        mediaOtherMapper.updateById(other);
    }

    /**
     * 进度驱动已观看标记：进度达看完阈值（{@link MediaHomeItemSupport#isFinished(Long, Long)}）时
     * 置 watched=true 并清零进度；否则照写进度，watched 保持原值（已看完再次观看不复活）。
     * durationMs 为空/非正时无法判定阈值，仅照写进度。
     */
    private void applyWatchedDrivenProgress(Boolean currentWatched,
                                            java.util.function.Consumer<Boolean> watchedSetter,
                                            java.util.function.Consumer<Long> progressSetter,
                                            long progressMs, Long durationMs) {
        if (MediaHomeItemSupport.isFinished(progressMs, durationMs)) {
            watchedSetter.accept(true);
            progressSetter.accept(0L);
        } else {
            if (Boolean.TRUE.equals(currentWatched)) {
                watchedSetter.accept(true);
            }
            progressSetter.accept(progressMs);
        }
    }

    @Override
    public void updateWatched(String itemId, MediaWatchedUpdateDto dto, String userId) {
        // 电影/剧/集/季/其他五类的已观看标记（工单 01/02）：标记已观看时清零进度，取消标记仅清标记。
        boolean watched = Boolean.TRUE.equals(dto.getWatched());
        MediaMovie movie = mediaMovieMapper.selectById(itemId);
        if (movie != null) {
            if (!userId.equals(movie.getUserId())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
            }
            movie.setWatched(watched);
            if (watched) {
                movie.setProgressMs(0L);
            }
            mediaMovieMapper.updateById(movie);
            return;
        }
        // 剧：整剧向下级联所有季与集同值（工单 02）
        MediaSeries series = mediaSeriesMapper.selectById(itemId);
        if (series != null) {
            if (!userId.equals(series.getUserId())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "电视剧不存在");
            }
            mediaWatchedLinkageSupport.cascadeSeries(series.getId(), watched);
            return;
        }
        MediaEpisode episode = mediaEpisodeMapper.selectById(itemId);
        if (episode != null) {
            MediaSeries owner = mediaSeriesMapper.selectById(episode.getSeriesId());
            if (owner == null || !userId.equals(owner.getUserId())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
            }
            boolean changed = !Objects.equals(episode.getWatched(), watched);
            episode.setWatched(watched);
            if (watched) {
                episode.setProgressMs(0L);
            }
            mediaEpisodeMapper.updateById(episode);
            // 集标记实际变化 → 重算父级联动（工单 02）
            if (changed) {
                mediaWatchedLinkageSupport.recomputeParents(episode.getSeasonId(), episode.getSeriesId());
            }
            return;
        }
        // 季：向下级联其所有集，并经剧间接校验归属（工单 02）
        MediaSeason season = mediaSeasonMapper.selectById(itemId);
        if (season != null) {
            MediaSeries owner = mediaSeriesMapper.selectById(season.getSeriesId());
            if (owner == null || !userId.equals(owner.getUserId())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "季不存在");
            }
            mediaWatchedLinkageSupport.cascadeSeason(season.getId(), watched);
            mediaWatchedLinkageSupport.recomputeParents(season.getId(), season.getSeriesId());
            return;
        }
        MediaOther other = mediaOtherMapper.selectById(itemId);
        if (other == null || !userId.equals(other.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
        }
        other.setWatched(watched);
        if (watched) {
            other.setProgressMs(0L);
        }
        mediaOtherMapper.updateById(other);
    }

    @Override
    public MediaItemDetailVo getItemDetail(String itemId, String userId) {
        // 电影详情（issue #18）、集详情（issue #17）、其他详情（issue #19）
        MediaMovie movie = mediaMovieMapper.selectById(itemId);
        if (movie != null) {
            return mediaMovieQuerySupport.getMovieDetail(itemId, userId);
        }
        MediaEpisode episode = mediaEpisodeMapper.selectById(itemId);
        if (episode != null) {
            return mediaTvQuerySupport.getEpisodeDetail(itemId, userId);
        }
        MediaOther other = mediaOtherMapper.selectById(itemId);
        if (other != null) {
            return mediaOtherQuerySupport.getOtherDetail(itemId, userId);
        }
        throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
    }

    @Override
    public MediaSeriesDetailVo getSeriesDetail(String seriesId, String userId) {
        return mediaTvQuerySupport.getSeriesDetail(seriesId, userId);
    }

    @Override
    public List<MediaItemVo> listSeasonEpisodes(String seriesId, String seasonId, String userId) {
        return mediaTvQuerySupport.listSeasonEpisodes(seriesId, seasonId, userId);
    }

    @Override
    public String getItemIdByFileNodeId(String fileNodeId, String userId) {
        // 新模型（issue #19）：其他行（文件级）→ 其他 ID；电影/集文件明细 → 标题级 ID
        MediaOther other = mediaOtherMapper.selectOne(new LambdaQueryWrapper<MediaOther>()
                .eq(MediaOther::getFileNodeId, fileNodeId)
                .eq(MediaOther::getUserId, userId));
        if (other != null) {
            return other.getId();
        }
        MediaMovieFile movieFile = mediaMovieFileMapper.selectOne(new LambdaQueryWrapper<MediaMovieFile>()
                .eq(MediaMovieFile::getFileNodeId, fileNodeId));
        if (movieFile != null) {
            MediaMovie movie = mediaMovieMapper.selectById(movieFile.getMovieId());
            if (movie != null && userId.equals(movie.getUserId())) {
                return movie.getId();
            }
        }
        MediaEpisodeFile episodeFile = mediaEpisodeFileMapper.selectOne(new LambdaQueryWrapper<MediaEpisodeFile>()
                .eq(MediaEpisodeFile::getFileNodeId, fileNodeId));
        if (episodeFile != null) {
            MediaEpisode episode = mediaEpisodeMapper.selectById(episodeFile.getEpisodeId());
            if (episode != null) {
                MediaSeries series = mediaSeriesMapper.selectById(episode.getSeriesId());
                if (series != null && userId.equals(series.getUserId())) {
                    return episode.getId();
                }
            }
        }
        throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
    }

    @Override
    public List<MediaGenreVo> listGenres(String userId, String directoryId) {
        return mediaGenreSupport.listGenres(userId, directoryId);
    }
}
