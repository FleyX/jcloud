package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.MediaMatchUpdateDto;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.dto.MediaProgressUpdateDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesVo;
import com.fleyx.jcloud.service.MediaItemService;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.MediaSeriesSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 媒体条目查询与匹配服务实现。
 */
@Service
@RequiredArgsConstructor
public class MediaItemServiceImpl implements MediaItemService {

    private final MediaItemMapper mediaItemMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final MediaSeriesMapper mediaSeriesMapper;
    private final FileMapper fileMapper;
    private final TmdbService tmdbService;
    private final MediaSeriesSupport mediaSeriesSupport;

    @Override
    public IPage<MediaItemVo> listMovies(String userId, MediaPageQueryDto query) {
        return queryItemPage(userId, MediaItemType.MOVIE.getCode(), query, true);
    }

    @Override
    public IPage<MediaSeriesVo> listSeries(String userId, MediaPageQueryDto query) {
        Page<MediaSeries> page = new Page<>(query.normalizedPageNum(), query.normalizedPageSize());
        IPage<MediaSeries> result = mediaSeriesMapper.selectSeriesPage(page, userId,
                blankToNull(query.getKeyword()), query.sortByRelease() ? MediaPageQueryDto.SORT_FIELD_RELEASE : MediaPageQueryDto.SORT_FIELD_ADDED,
                query.asc());
        List<MediaSeries> seriesList = result.getRecords();
        Map<String, MediaMetadata> metadataMap = loadMetadataMapByIds(
                seriesList.stream().map(MediaSeries::getMetadataId).toList());
        Map<String, List<MediaItem>> episodeMap = loadEpisodeMap(userId, seriesList.stream().map(MediaSeries::getId).toList());

        List<MediaSeriesVo> vos = new ArrayList<>();
        for (MediaSeries series : seriesList) {
            List<MediaItem> episodes = episodeMap.getOrDefault(series.getId(), List.of());
            MediaMetadata metadata = series.getMetadataId() == null ? null : metadataMap.get(series.getMetadataId());
            MediaSeriesVo vo = new MediaSeriesVo();
            vo.setSeriesName(series.getSeriesName());
            vo.setEpisodeCount((long) episodes.size());
            vo.setMatchStatus(series.getMatchStatus());
            vo.setLastPlayTime(episodes.stream().map(MediaItem::getLastPlayTime)
                    .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null));
            vo.setMetadataId(series.getMetadataId());
            vo.setTitle(metadata != null ? metadata.getTitle() : series.getSeriesName());
            vo.setReleaseDate(metadata == null ? null : metadata.getReleaseDate());
            vo.setVoteAverage(metadata == null ? null : metadata.getVoteAverage());
            vo.setPosterUrl(metadata == null ? null : posterUrlOf(metadata));
            vos.add(vo);
        }
        Page<MediaSeriesVo> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(vos);
        return voPage;
    }

    /**
     * 批量加载指定剧的所有集（用于统计集数与最近播放时间）。
     */
    private Map<String, List<MediaItem>> loadEpisodeMap(String userId, List<String> seriesIds) {
        if (seriesIds.isEmpty()) {
            return Map.of();
        }
        return mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                        .eq(MediaItem::getUserId, userId)
                        .in(MediaItem::getSeriesId, seriesIds))
                .stream().collect(Collectors.groupingBy(MediaItem::getSeriesId));
    }

    @Override
    public List<MediaItemVo> listEpisodes(String seriesName, String userId) {
        List<MediaItem> items = mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                .eq(MediaItem::getUserId, userId)
                .eq(MediaItem::getItemType, MediaItemType.EPISODE.getCode())
                .eq(MediaItem::getSeriesName, seriesName));
        items.sort(Comparator.comparing(MediaItem::getSeasonNo, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(MediaItem::getEpisodeNo, Comparator.nullsLast(Integer::compareTo)));
        return toItemVos(items, true);
    }

    @Override
    public IPage<MediaItemVo> listOthers(String userId, MediaPageQueryDto query) {
        return queryItemPage(userId, MediaItemType.OTHER.getCode(), query, false);
    }

    /**
     * 分页查询条目并转换为视图分页。
     */
    private IPage<MediaItemVo> queryItemPage(String userId, String itemType, MediaPageQueryDto query, boolean withMetadata) {
        Page<MediaItem> page = new Page<>(query.normalizedPageNum(), query.normalizedPageSize());
        IPage<MediaItem> result = mediaItemMapper.selectItemPage(page, userId, itemType,
                blankToNull(query.getKeyword()),
                query.sortByRelease() ? MediaPageQueryDto.SORT_FIELD_RELEASE : MediaPageQueryDto.SORT_FIELD_ADDED,
                query.asc());
        Page<MediaItemVo> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(toItemVos(result.getRecords(), withMetadata));
        return voPage;
    }

    private String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaItemVo updateMatch(String itemId, MediaMatchUpdateDto dto, String userId) {
        MediaItem item = requireOwned(itemId, userId);
        MediaMetadata metadata = tmdbService.getOrFetch(dto.getTmdbId(), dto.getMediaType());
        item.setMetadataId(metadata.getId());
        item.setMatchStatus(MediaMatchStatus.MANUAL.getCode());
        mediaItemMapper.updateById(item);
        return toItemVos(List.of(item), true).getFirst();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSeriesMatch(String seriesName, MediaMatchUpdateDto dto, String userId) {
        MediaMetadata metadata = tmdbService.getOrFetch(dto.getTmdbId(), "tv");
        MediaSeries series = mediaSeriesMapper.selectOne(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getUserId, userId)
                .eq(MediaSeries::getSeriesName, seriesName));
        if (series == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "电视剧不存在");
        }
        MediaSeries update = new MediaSeries();
        update.setId(series.getId());
        update.setMetadataId(metadata.getId());
        update.setMatchStatus(MediaMatchStatus.MANUAL.getCode());
        mediaSeriesMapper.updateById(update);
        // 复用削刮管线补齐季/集元数据（已单独手动修正的集不覆盖）
        mediaSeriesSupport.applySeriesMetadata(series, metadata, MediaMatchStatus.MANUAL.getCode());
    }

    @Override
    public void updateProgress(String itemId, MediaProgressUpdateDto dto, String userId) {
        MediaItem item = requireOwned(itemId, userId);
        MediaItem update = new MediaItem();
        update.setId(item.getId());
        update.setProgressMs(dto.getProgressMs());
        update.setLastPlayTime(LocalDateTime.now());
        mediaItemMapper.updateById(update);
    }

    @Override
    public MediaItemDetailVo getItemDetail(String itemId, String userId) {
        MediaItem item = requireOwned(itemId, userId);
        FileNode node = fileMapper.selectById(item.getFileNodeId());
        MediaMetadata metadata = item.getMetadataId() == null ? null
                : mediaMetadataMapper.selectById(item.getMetadataId());

        MediaItemDetailVo vo = new MediaItemDetailVo();
        vo.setId(item.getId());
        vo.setItemType(item.getItemType());
        vo.setFileName(node == null ? null : node.getName());
        vo.setFileSize(node == null ? null : node.getSize());
        vo.setMatchStatus(item.getMatchStatus());
        vo.setMetadataId(item.getMetadataId());
        vo.setSeriesName(item.getSeriesName());
        vo.setSeasonNo(item.getSeasonNo());
        vo.setEpisodeNo(item.getEpisodeNo());
        vo.setDurationMs(item.getDurationMs());
        vo.setProgressMs(item.getProgressMs());
        if (item.getSeriesId() != null) {
            MediaSeries series = mediaSeriesMapper.selectById(item.getSeriesId());
            if (series != null) {
                vo.setSeriesMetadataId(series.getMetadataId());
            }
        }
        vo.setWidth(item.getWidth());
        vo.setHeight(item.getHeight());
        vo.setVideoCodec(item.getVideoCodec());
        vo.setAudioCodec(item.getAudioCodec());
        if (metadata != null) {
            vo.setTitle(metadata.getTitle());
            vo.setOriginalTitle(metadata.getOriginalTitle());
            vo.setOverview(metadata.getOverview());
            vo.setGenres(splitGenres(metadata.getGenres()));
            vo.setReleaseDate(metadata.getReleaseDate());
            vo.setVoteAverage(metadata.getVoteAverage());
            vo.setPosterUrl(posterUrlOf(metadata));
            vo.setBackdropUrl(backdropUrlOf(metadata));
        }
        if (vo.getTitle() == null) {
            vo.setTitle(vo.getFileName());
        }
        return vo;
    }

    @Override
    public MediaSeriesDetailVo getSeriesDetail(String seriesName, String userId) {
        List<MediaItemVo> episodes = listEpisodes(seriesName, userId);
        if (episodes.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "电视剧不存在");
        }
        MediaSeries series = mediaSeriesMapper.selectOne(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getUserId, userId)
                .eq(MediaSeries::getSeriesName, seriesName));
        MediaMetadata metadata = series == null || series.getMetadataId() == null ? null
                : mediaMetadataMapper.selectById(series.getMetadataId());

        MediaSeriesDetailVo vo = new MediaSeriesDetailVo();
        vo.setSeriesName(seriesName);
        vo.setMatchStatus(series == null ? episodes.getFirst().getMatchStatus() : series.getMatchStatus());
        vo.setMetadataId(series == null ? null : series.getMetadataId());
        vo.setEpisodes(episodes);
        if (metadata != null) {
            vo.setTitle(metadata.getTitle());
            vo.setOriginalTitle(metadata.getOriginalTitle());
            vo.setOverview(metadata.getOverview());
            vo.setGenres(splitGenres(metadata.getGenres()));
            vo.setReleaseDate(metadata.getReleaseDate());
            vo.setVoteAverage(metadata.getVoteAverage());
            vo.setSeasonCount(metadata.getSeasonCount());
            vo.setPosterUrl(posterUrlOf(metadata));
            vo.setBackdropUrl(backdropUrlOf(metadata));
        }
        if (vo.getTitle() == null) {
            vo.setTitle(seriesName);
        }
        return vo;
    }

    private List<String> splitGenres(String genres) {
        if (genres == null || genres.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(genres.split(",")).filter(g -> !g.isBlank()).toList();
    }

    private String posterUrlOf(MediaMetadata metadata) {
        return metadata.getPosterPath() == null ? null
                : "/jcloud/api/media/metadata/" + metadata.getId() + "/poster";
    }

    private String backdropUrlOf(MediaMetadata metadata) {
        return metadata.getBackdropPath() == null ? null
                : "/jcloud/api/media/metadata/" + metadata.getId() + "/backdrop";
    }

    private Map<String, MediaMetadata> loadMetadataMapByIds(List<String> metadataIds) {
        List<String> ids = metadataIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return mediaMetadataMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(MediaMetadata::getId, Function.identity()));
    }

    private MediaItem requireOwned(String itemId, String userId) {
        MediaItem item = mediaItemMapper.selectById(itemId);
        if (item == null || !userId.equals(item.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
        }
        return item;
    }

    private Map<String, MediaMetadata> loadMetadataMap(List<MediaItem> items) {
        List<String> metadataIds = items.stream().map(MediaItem::getMetadataId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (metadataIds.isEmpty()) {
            return Map.of();
        }
        return mediaMetadataMapper.selectBatchIds(metadataIds).stream()
                .collect(Collectors.toMap(MediaMetadata::getId, Function.identity()));
    }

    private List<MediaItemVo> toItemVos(List<MediaItem> items, boolean withMetadata) {
        Map<String, MediaMetadata> metadataMap = withMetadata ? loadMetadataMap(items) : Map.of();
        Map<String, String> fileNameMap = loadFileNameMap(items);
        List<MediaItemVo> result = new ArrayList<>();
        for (MediaItem item : items) {
            MediaItemVo vo = new MediaItemVo();
            vo.setId(item.getId());
            vo.setFileNodeId(item.getFileNodeId());
            vo.setItemType(item.getItemType());
            vo.setFileName(fileNameMap.get(item.getFileNodeId()));
            vo.setMatchStatus(item.getMatchStatus());
            vo.setMetadataId(item.getMetadataId());
            vo.setSeasonNo(item.getSeasonNo());
            vo.setEpisodeNo(item.getEpisodeNo());
            vo.setDurationMs(item.getDurationMs());
            vo.setProgressMs(item.getProgressMs());
            vo.setLastPlayTime(item.getLastPlayTime());
            MediaMetadata metadata = item.getMetadataId() == null ? null : metadataMap.get(item.getMetadataId());
            if (metadata != null) {
                vo.setTitle(metadata.getTitle());
                vo.setReleaseDate(metadata.getReleaseDate());
                vo.setVoteAverage(metadata.getVoteAverage());
                if (metadata.getPosterPath() != null) {
                    vo.setPosterUrl("/jcloud/api/media/metadata/" + metadata.getId() + "/poster");
                }
            }
            if (vo.getTitle() == null) {
                vo.setTitle(vo.getFileName());
            }
            result.add(vo);
        }
        return result;
    }

    private Map<String, String> loadFileNameMap(List<MediaItem> items) {
        List<String> fileNodeIds = items.stream().map(MediaItem::getFileNodeId).distinct().toList();
        if (fileNodeIds.isEmpty()) {
            return Map.of();
        }
        return fileMapper.selectBatchIds(fileNodeIds).stream()
                .collect(Collectors.toMap(FileNode::getId, FileNode::getName));
    }
}
