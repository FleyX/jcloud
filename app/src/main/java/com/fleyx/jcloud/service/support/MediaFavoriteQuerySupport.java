package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.enums.MediaFavoriteOwnerType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaFavoriteMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.MediaFavoriteQueryDto;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaFavorite;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.MediaFavoriteVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 我的收藏分页查询支撑组件（工单 02）。
 * <p>
 * 按归属实体类型（movie/series/season/episode/other）独立分页查询 t_media_favorite，
 * 按收藏时间倒序；批量装配实体行 + 元数据组装卡片 VO。
 * 库过滤：movie/series/other 按 directory_id 直查；season/episode 无 directory_id，
 * 经 series_id → t_media_series.directory_id 归属判定。
 */
@Component
@RequiredArgsConstructor
public class MediaFavoriteQuerySupport {

    private final MediaFavoriteMapper mediaFavoriteMapper;
    private final MediaMovieMapper mediaMovieMapper;
    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaOtherMapper mediaOtherMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final MediaItemVoSupport mediaItemVoSupport;

    /**
     * 分页查询当前用户某类实体的收藏（按收藏时间倒序）。
     */
    public IPage<MediaFavoriteVo> pageFavorites(String userId, MediaFavoriteQueryDto query) {
        MediaFavoriteOwnerType ownerType = MediaFavoriteOwnerType.of(query.getOwnerType());
        if (ownerType == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "收藏类型不合法");
        }
        Page<MediaFavorite> page = new Page<>(query.normalizedPageNum(), query.normalizedPageSize());
        LambdaQueryWrapper<MediaFavorite> wrapper = new LambdaQueryWrapper<MediaFavorite>()
                .eq(MediaFavorite::getUserId, userId)
                .eq(MediaFavorite::getOwnerType, ownerType.getCode())
                .orderByDesc(MediaFavorite::getCreateTime)
                .orderByDesc(MediaFavorite::getId);
        // 归属实体仍存在的过滤：total/records 与级联清理后可能残留的孤儿收藏保持一致（与用户隔离同一语义）
        wrapper.apply(ownerExistsSql(ownerType), userId);
        List<String> allowedOwnerIds = resolveAllowedOwnerIds(userId, ownerType, query.getDirectoryId());
        if (allowedOwnerIds != null) {
            if (allowedOwnerIds.isEmpty()) {
                return emptyPage(page);
            }
            wrapper.in(MediaFavorite::getOwnerId, allowedOwnerIds);
        }

        IPage<MediaFavorite> result = mediaFavoriteMapper.selectPage(page, wrapper);
        List<MediaFavoriteVo> vos = toVos(ownerType, result.getRecords());
        Page<MediaFavoriteVo> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(vos);
        return voPage;
    }

    /**
     * 归属实体仍存在的 EXISTS 子查询（按类型对应用户隔离，{0} 为 userId）。
     * 季/集表无 user_id 列，经所属剧 t_media_series 归属判定。
     */
    private String ownerExistsSql(MediaFavoriteOwnerType ownerType) {
        return switch (ownerType) {
            case MOVIE -> "EXISTS (SELECT 1 FROM t_media_movie m WHERE m.id = t_media_favorite.owner_id AND m.user_id = {0})";
            case SERIES -> "EXISTS (SELECT 1 FROM t_media_series s WHERE s.id = t_media_favorite.owner_id AND s.user_id = {0})";
            case SEASON -> "EXISTS (SELECT 1 FROM t_media_season s JOIN t_media_series srs ON srs.id = s.series_id WHERE s.id = t_media_favorite.owner_id AND srs.user_id = {0})";
            case EPISODE -> "EXISTS (SELECT 1 FROM t_media_episode e JOIN t_media_series srs ON srs.id = e.series_id WHERE e.id = t_media_favorite.owner_id AND srs.user_id = {0})";
            case OTHER -> "EXISTS (SELECT 1 FROM t_media_other o WHERE o.id = t_media_favorite.owner_id AND o.user_id = {0})";
        };
    }

    /**
     * 库过滤时解析该库合法 ownerId 集合；无库过滤返回 null（不限制）。
     * season/episode 经 series_id 归属该库 series 列表间接过滤（并限定用户）。
     */
    private List<String> resolveAllowedOwnerIds(String userId, MediaFavoriteOwnerType ownerType, String directoryId) {
        if (directoryId == null || directoryId.isBlank()) {
            return null;
        }
        return switch (ownerType) {
            case MOVIE -> selectIds(mediaMovieMapper.selectList(new LambdaQueryWrapper<MediaMovie>()
                    .eq(MediaMovie::getDirectoryId, directoryId)
                    .eq(MediaMovie::getUserId, userId)
                    .select(MediaMovie::getId)));
            case SERIES -> selectIds(mediaSeriesMapper.selectList(new LambdaQueryWrapper<MediaSeries>()
                    .eq(MediaSeries::getDirectoryId, directoryId)
                    .eq(MediaSeries::getUserId, userId)
                    .select(MediaSeries::getId)));
            case OTHER -> selectIds(mediaOtherMapper.selectList(new LambdaQueryWrapper<MediaOther>()
                    .eq(MediaOther::getDirectoryId, directoryId)
                    .eq(MediaOther::getUserId, userId)
                    .select(MediaOther::getId)));
            case SEASON -> {
                List<String> seriesIds = seriesIdsOf(directoryId, userId);
                yield seriesIds.isEmpty() ? List.of() : selectIds(mediaSeasonMapper.selectList(
                        new LambdaQueryWrapper<MediaSeason>()
                                .in(MediaSeason::getSeriesId, seriesIds)
                                .select(MediaSeason::getId)));
            }
            case EPISODE -> {
                List<String> seriesIds = seriesIdsOf(directoryId, userId);
                yield seriesIds.isEmpty() ? List.of() : selectIds(mediaEpisodeMapper.selectList(
                        new LambdaQueryWrapper<MediaEpisode>()
                                .in(MediaEpisode::getSeriesId, seriesIds)
                                .select(MediaEpisode::getId)));
            }
        };
    }

    private List<String> seriesIdsOf(String directoryId, String userId) {
        return selectIds(mediaSeriesMapper.selectList(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getDirectoryId, directoryId)
                .eq(MediaSeries::getUserId, userId)
                .select(MediaSeries::getId)));
    }

    /**
     * 收藏行批量装配为卡片 VO；实体已被删但收藏残留的行跳过（正常级联已清理）。
     */
    private List<MediaFavoriteVo> toVos(MediaFavoriteOwnerType ownerType, List<MediaFavorite> favorites) {
        if (favorites.isEmpty()) {
            return List.of();
        }
        return switch (ownerType) {
            case MOVIE -> buildMovieVos(favorites);
            case SERIES -> buildSeriesVos(favorites);
            case SEASON -> buildSeasonVos(favorites);
            case EPISODE -> buildEpisodeVos(favorites);
            case OTHER -> buildOtherVos(favorites);
        };
    }

    private List<MediaFavoriteVo> buildMovieVos(List<MediaFavorite> favorites) {
        Map<String, MediaMovie> movieMap = mediaMovieMapper.selectBatchIds(ownerIds(favorites)).stream()
                .collect(Collectors.toMap(MediaMovie::getId, Function.identity()));
        Map<String, MediaMetadata> metadataMap = loadMetadataMap(
                movieMap.values().stream().map(MediaMovie::getMetadataId).toList());
        Map<String, Long> nodeVersionMap = mediaItemVoSupport.loadNodeVersionMap(
                metadataMap.values().stream().map(MediaMetadata::getPosterFileNodeId).toList());
        List<MediaFavoriteVo> result = new ArrayList<>();
        for (MediaFavorite favorite : favorites) {
            MediaMovie movie = movieMap.get(favorite.getOwnerId());
            if (movie == null) {
                continue;
            }
            MediaMetadata metadata = movie.getMetadataId() == null ? null : metadataMap.get(movie.getMetadataId());
            MediaFavoriteVo vo = baseVo(favorite);
            vo.setTitle(metadata != null && metadata.getTitle() != null ? metadata.getTitle() : movie.getTitle());
            vo.setPosterUrl(mediaItemVoSupport.posterUrlOf(metadata, nodeVersionMap));
            vo.setReleaseDate(metadata == null ? null : metadata.getReleaseDate());
            vo.setVoteAverage(metadata == null ? null : metadata.getVoteAverage());
            vo.setMatchStatus(movie.getMatchStatus());
            result.add(vo);
        }
        return result;
    }

    private List<MediaFavoriteVo> buildSeriesVos(List<MediaFavorite> favorites) {
        Map<String, MediaSeries> seriesMap = mediaSeriesMapper.selectBatchIds(ownerIds(favorites)).stream()
                .collect(Collectors.toMap(MediaSeries::getId, Function.identity()));
        Map<String, MediaMetadata> metadataMap = loadMetadataMap(
                seriesMap.values().stream().map(MediaSeries::getMetadataId).toList());
        Map<String, Long> nodeVersionMap = mediaItemVoSupport.loadNodeVersionMap(
                metadataMap.values().stream().map(MediaMetadata::getPosterFileNodeId).toList());
        List<MediaFavoriteVo> result = new ArrayList<>();
        for (MediaFavorite favorite : favorites) {
            MediaSeries series = seriesMap.get(favorite.getOwnerId());
            if (series == null) {
                continue;
            }
            MediaMetadata metadata = series.getMetadataId() == null ? null : metadataMap.get(series.getMetadataId());
            MediaFavoriteVo vo = baseVo(favorite);
            vo.setTitle(metadata != null && metadata.getTitle() != null ? metadata.getTitle() : series.getSeriesName());
            vo.setPosterUrl(mediaItemVoSupport.posterUrlOf(metadata, nodeVersionMap));
            vo.setReleaseDate(metadata == null ? null : metadata.getReleaseDate());
            vo.setVoteAverage(metadata == null ? null : metadata.getVoteAverage());
            vo.setMatchStatus(series.getMatchStatus());
            result.add(vo);
        }
        return result;
    }

    private List<MediaFavoriteVo> buildSeasonVos(List<MediaFavorite> favorites) {
        Map<String, MediaSeason> seasonMap = mediaSeasonMapper.selectBatchIds(ownerIds(favorites)).stream()
                .collect(Collectors.toMap(MediaSeason::getId, Function.identity()));
        Map<String, MediaSeries> seriesMap = loadSeriesMap(
                seasonMap.values().stream().map(MediaSeason::getSeriesId).toList());
        Map<String, MediaMetadata> metadataMap = loadMetadataMap(
                seasonMap.values().stream().map(MediaSeason::getMetadataId).toList());
        Map<String, Long> nodeVersionMap = mediaItemVoSupport.loadNodeVersionMap(
                metadataMap.values().stream().map(MediaMetadata::getPosterFileNodeId).toList());
        List<MediaFavoriteVo> result = new ArrayList<>();
        for (MediaFavorite favorite : favorites) {
            MediaSeason season = seasonMap.get(favorite.getOwnerId());
            if (season == null) {
                continue;
            }
            MediaSeries series = seriesMap.get(season.getSeriesId());
            if (series == null) {
                continue;
            }
            MediaMetadata metadata = season.getMetadataId() == null ? null : metadataMap.get(season.getMetadataId());
            MediaFavoriteVo vo = baseVo(favorite);
            vo.setTitle(metadata == null ? null : metadata.getTitle());
            vo.setPosterUrl(mediaItemVoSupport.posterUrlOf(metadata, nodeVersionMap));
            vo.setSeriesId(series.getId());
            vo.setSeriesName(series.getSeriesName());
            vo.setSeasonNo(season.getSeasonNo());
            vo.setMatchStatus(series.getMatchStatus());
            result.add(vo);
        }
        return result;
    }

    private List<MediaFavoriteVo> buildEpisodeVos(List<MediaFavorite> favorites) {
        Map<String, MediaEpisode> episodeMap = mediaEpisodeMapper.selectBatchIds(ownerIds(favorites)).stream()
                .collect(Collectors.toMap(MediaEpisode::getId, Function.identity()));
        Map<String, MediaSeries> seriesMap = loadSeriesMap(
                episodeMap.values().stream().map(MediaEpisode::getSeriesId).toList());
        Map<String, MediaSeason> seasonMap = loadSeasonMap(
                episodeMap.values().stream().map(MediaEpisode::getSeasonId).toList());
        Map<String, MediaMetadata> metadataMap = loadMetadataMap(
                episodeMap.values().stream().map(MediaEpisode::getMetadataId).toList());
        Map<String, MediaMetadata> seriesMetadataMap = loadMetadataMap(
                seriesMap.values().stream().map(MediaSeries::getMetadataId).toList());
        Map<String, Long> nodeVersionMap = mediaItemVoSupport.loadNodeVersionMap(
                Stream.concat(metadataMap.values().stream(), seriesMetadataMap.values().stream())
                        .map(MediaMetadata::getPosterFileNodeId)
                        .toList());
        List<MediaFavoriteVo> result = new ArrayList<>();
        for (MediaFavorite favorite : favorites) {
            MediaEpisode episode = episodeMap.get(favorite.getOwnerId());
            if (episode == null) {
                continue;
            }
            MediaSeries series = seriesMap.get(episode.getSeriesId());
            if (series == null) {
                continue;
            }
            MediaSeason season = seasonMap.get(episode.getSeasonId());
            MediaMetadata metadata = episode.getMetadataId() == null ? null : metadataMap.get(episode.getMetadataId());
            MediaFavoriteVo vo = baseVo(favorite);
            vo.setTitle(metadata == null ? null : metadata.getTitle());
            // 集自身无海报时回退剧集海报
            vo.setPosterUrl(episodePosterUrl(metadata, series, seriesMetadataMap, nodeVersionMap));
            vo.setSeriesId(series.getId());
            vo.setSeriesName(series.getSeriesName());
            vo.setSeasonNo(season == null ? null : season.getSeasonNo());
            vo.setEpisodeNo(episode.getEpisodeNo());
            vo.setMatchStatus(series.getMatchStatus());
            result.add(vo);
        }
        return result;
    }

    private List<MediaFavoriteVo> buildOtherVos(List<MediaFavorite> favorites) {
        Map<String, MediaOther> otherMap = mediaOtherMapper.selectBatchIds(ownerIds(favorites)).stream()
                .collect(Collectors.toMap(MediaOther::getId, Function.identity()));
        List<MediaFavoriteVo> result = new ArrayList<>();
        for (MediaFavorite favorite : favorites) {
            MediaOther other = otherMap.get(favorite.getOwnerId());
            if (other == null) {
                continue;
            }
            MediaFavoriteVo vo = baseVo(favorite);
            vo.setTitle(other.getName());
            vo.setFileName(other.getName());
            vo.setFileNodeId(other.getFileNodeId());
            vo.setDurationMs(other.getDurationMs());
            result.add(vo);
        }
        return result;
    }

    private MediaFavoriteVo baseVo(MediaFavorite favorite) {
        MediaFavoriteVo vo = new MediaFavoriteVo();
        vo.setOwnerType(favorite.getOwnerType());
        vo.setOwnerId(favorite.getOwnerId());
        vo.setFavorited(true);
        vo.setFavoriteTime(favorite.getCreateTime());
        return vo;
    }

    /**
     * 集海报：集自身元数据有海报用自身，否则回退所属剧海报。
     */
    private String episodePosterUrl(MediaMetadata episodeMetadata, MediaSeries series,
                                    Map<String, MediaMetadata> seriesMetadataMap,
                                    Map<String, Long> nodeVersionMap) {
        String url = mediaItemVoSupport.posterUrlOf(episodeMetadata, nodeVersionMap);
        if (url != null) {
            return url;
        }
        MediaMetadata seriesMetadata = series.getMetadataId() == null ? null : seriesMetadataMap.get(series.getMetadataId());
        return mediaItemVoSupport.posterUrlOf(seriesMetadata, nodeVersionMap);
    }

    private Map<String, MediaSeries> loadSeriesMap(List<String> seriesIds) {
        List<String> ids = seriesIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return mediaSeriesMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(MediaSeries::getId, Function.identity()));
    }

    private Map<String, MediaSeason> loadSeasonMap(List<String> seasonIds) {
        List<String> ids = seasonIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return mediaSeasonMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(MediaSeason::getId, Function.identity()));
    }

    private Map<String, MediaMetadata> loadMetadataMap(List<String> metadataIds) {
        List<String> ids = metadataIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return mediaMetadataMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(MediaMetadata::getId, Function.identity()));
    }

    private List<String> ownerIds(List<MediaFavorite> favorites) {
        return favorites.stream().map(MediaFavorite::getOwnerId).toList();
    }

    private List<String> selectIds(List<? extends com.fleyx.jcloud.model.po.BaseEntity> entities) {
        return entities.stream().map(com.fleyx.jcloud.model.po.BaseEntity::getId).toList();
    }

    private IPage<MediaFavoriteVo> emptyPage(Page<MediaFavorite> page) {
        Page<MediaFavoriteVo> empty = new Page<>(page.getCurrent(), page.getSize(), 0);
        empty.setRecords(List.of());
        return empty;
    }
}
