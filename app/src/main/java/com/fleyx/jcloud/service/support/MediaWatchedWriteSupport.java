package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.MediaProgressUpdateDto;
import com.fleyx.jcloud.model.dto.MediaWatchedUpdateDto;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 媒体播放进度与已观看标记写入支撑（从 {@code MediaItemServiceImpl} 拆分规避 400 行上限）。
 * <p>
 * 播放进度记录到标题级新行（issue #19）：电影 → t_media_movie、集 → t_media_episode、
 * 其他 → t_media_other；一部电影多版本共享进度，续播按 last_play_file_id 定位版本文件。
 * 进度驱动已观看（工单 01）：进度达看完阈值自动置 watched 并清零进度，否则照写进度。
 * 已观看标记（工单 01/02）：电影/剧/季/集/其他五类；标记已观看时清零进度，取消标记仅清标记。
 * 归属校验统一收敛于本类的 {@code requireOwned*} 助手（不存在返回 null 供上层继续探测其它类型）。
 */
@Component
@RequiredArgsConstructor
public class MediaWatchedWriteSupport {

    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaMovieMapper mediaMovieMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaOtherMapper mediaOtherMapper;
    private final MediaPlaybackResolveSupport mediaPlaybackResolveSupport;
    private final MediaWatchedLinkageSupport mediaWatchedLinkageSupport;

    public void updateProgress(String itemId, MediaProgressUpdateDto dto, String userId) {
        LocalDateTime now = LocalDateTime.now();
        MediaMovie movie = requireOwnedMovie(itemId, userId);
        if (movie != null) {
            // 指定版本时校验该明细行属于此电影并记为该次播放版本，缺省按续播定位（last_play_file_id 优先）
            MediaMovieFile file = dto.getVersionId() == null
                    ? mediaPlaybackResolveSupport.pickMovieFile(movie)
                    : mediaPlaybackResolveSupport.pickVersion(movie, dto.getVersionId());
            movie.setLastPlayTime(now);
            if (file != null) {
                movie.setLastPlayFileId(file.getId());
            }
            MediaHomeItemSupport.WatchedProgressState wp = MediaHomeItemSupport.applyWatchedDrivenProgress(
                    movie.getWatched(), dto.getProgressMs(), file == null ? null : file.getDurationMs());
            movie.setWatched(wp.watched());
            movie.setProgressMs(wp.progressMs());
            mediaMovieMapper.updateById(movie);
            return;
        }
        MediaEpisode episode = requireOwnedEpisode(itemId, userId);
        if (episode != null) {
            MediaEpisodeFile file = mediaPlaybackResolveSupport.pickEpisodeFile(episode);
            episode.setLastPlayTime(now);
            if (file != null) {
                episode.setLastPlayFileId(file.getId());
            }
            boolean beforeWatched = Boolean.TRUE.equals(episode.getWatched());
            MediaHomeItemSupport.WatchedProgressState wp = MediaHomeItemSupport.applyWatchedDrivenProgress(
                    episode.getWatched(), dto.getProgressMs(), file == null ? null : file.getDurationMs());
            episode.setWatched(wp.watched());
            episode.setProgressMs(wp.progressMs());
            mediaEpisodeMapper.updateById(episode);
            // 集由未看转已看（进度驱动）→ 重算父级联动（工单 02）
            boolean afterWatched = Boolean.TRUE.equals(episode.getWatched());
            if (!beforeWatched && afterWatched) {
                mediaWatchedLinkageSupport.recomputeParentsOf(episode);
            }
            return;
        }
        MediaOther other = requireOwnedOther(itemId, userId);
        if (other == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
        }
        other.setLastPlayTime(now);
        MediaHomeItemSupport.WatchedProgressState wp = MediaHomeItemSupport.applyWatchedDrivenProgress(
                other.getWatched(), dto.getProgressMs(), other.getDurationMs());
        other.setWatched(wp.watched());
        other.setProgressMs(wp.progressMs());
        mediaOtherMapper.updateById(other);
    }

    public void updateWatched(String itemId, MediaWatchedUpdateDto dto, String userId) {
        boolean watched = Boolean.TRUE.equals(dto.getWatched());
        MediaMovie movie = requireOwnedMovie(itemId, userId);
        if (movie != null) {
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
        MediaEpisode episode = requireOwnedEpisode(itemId, userId);
        if (episode != null) {
            boolean changed = !Objects.equals(episode.getWatched(), watched);
            episode.setWatched(watched);
            if (watched) {
                episode.setProgressMs(0L);
            }
            mediaEpisodeMapper.updateById(episode);
            // 集标记实际变化 → 重算父级联动（工单 02）
            if (changed) {
                mediaWatchedLinkageSupport.recomputeParentsOf(episode);
            }
            return;
        }
        // 季：向下级联其所有集，并经剧间接校验归属（工单 02）
        MediaSeason season = requireOwnedSeason(itemId, userId);
        if (season != null) {
            mediaWatchedLinkageSupport.cascadeSeason(season.getId(), watched);
            mediaWatchedLinkageSupport.recomputeParents(season.getId(), season.getSeriesId());
            return;
        }
        MediaOther other = requireOwnedOther(itemId, userId);
        if (other == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
        }
        other.setWatched(watched);
        if (watched) {
            other.setProgressMs(0L);
        }
        mediaOtherMapper.updateById(other);
    }

    /**
     * 归属解析：电影存在但非本用户时抛 NOT_FOUND；不存在（非本条目类型）返回 null 供上层继续探测。
     */
    private MediaMovie requireOwnedMovie(String itemId, String userId) {
        MediaMovie movie = mediaMovieMapper.selectById(itemId);
        if (movie == null) {
            return null;
        }
        if (!userId.equals(movie.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
        }
        return movie;
    }

    /**
     * 归属解析：集存在但经剧校验非本用户时抛 NOT_FOUND；集不存在返回 null 供上层继续探测。
     */
    private MediaEpisode requireOwnedEpisode(String itemId, String userId) {
        MediaEpisode episode = mediaEpisodeMapper.selectById(itemId);
        if (episode == null) {
            return null;
        }
        MediaSeries owner = mediaSeriesMapper.selectById(episode.getSeriesId());
        if (owner == null || !userId.equals(owner.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
        }
        return episode;
    }

    /**
     * 归属解析：季存在但经剧校验非本用户时抛 NOT_FOUND；季不存在返回 null 供上层继续探测。
     */
    private MediaSeason requireOwnedSeason(String itemId, String userId) {
        MediaSeason season = mediaSeasonMapper.selectById(itemId);
        if (season == null) {
            return null;
        }
        MediaSeries owner = mediaSeriesMapper.selectById(season.getSeriesId());
        if (owner == null || !userId.equals(owner.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "季不存在");
        }
        return season;
    }

    /**
     * 归属解析：其他行存在但非本用户时抛 NOT_FOUND；不存在返回 null 供上层继续探测。
     */
    private MediaOther requireOwnedOther(String itemId, String userId) {
        MediaOther other = mediaOtherMapper.selectById(itemId);
        if (other == null) {
            return null;
        }
        if (!userId.equals(other.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
        }
        return other;
    }
}
