package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.model.dto.MediaMatchUpdateDto;
import com.fleyx.jcloud.model.dto.MediaProgressUpdateDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesVo;
import com.fleyx.jcloud.service.MediaItemService;
import com.fleyx.jcloud.service.TmdbService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private final FileMapper fileMapper;
    private final TmdbService tmdbService;

    @Override
    public List<MediaItemVo> listMovies(String userId) {
        List<MediaItem> items = queryItems(userId, MediaItemType.MOVIE.getCode());
        return toItemVos(items, true);
    }

    @Override
    public List<MediaSeriesVo> listSeries(String userId) {
        List<MediaItem> items = queryItems(userId, MediaItemType.EPISODE.getCode());
        Map<String, MediaMetadata> metadataMap = loadMetadataMap(items);
        Map<String, List<MediaItem>> grouped = items.stream()
                .filter(i -> i.getSeriesName() != null)
                .collect(Collectors.groupingBy(MediaItem::getSeriesName, LinkedHashMap::new, Collectors.toList()));

        List<MediaSeriesVo> result = new ArrayList<>();
        for (Map.Entry<String, List<MediaItem>> entry : grouped.entrySet()) {
            List<MediaItem> episodes = entry.getValue();
            MediaSeriesVo vo = new MediaSeriesVo();
            vo.setSeriesName(entry.getKey());
            vo.setEpisodeCount((long) episodes.size());
            MediaItem first = episodes.stream()
                    .filter(e -> e.getMetadataId() != null).findFirst().orElse(episodes.getFirst());
            vo.setMatchStatus(first.getMatchStatus());
            vo.setLastPlayTime(episodes.stream().map(MediaItem::getLastPlayTime)
                    .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null));
            MediaMetadata metadata = first.getMetadataId() == null ? null : metadataMap.get(first.getMetadataId());
            vo.setMetadataId(first.getMetadataId());
            vo.setTitle(metadata != null ? metadata.getTitle() : entry.getKey());
            vo.setReleaseDate(metadata == null ? null : metadata.getReleaseDate());
            vo.setVoteAverage(metadata == null ? null : metadata.getVoteAverage());
            vo.setPosterUrl(metadata == null || metadata.getPosterPath() == null ? null
                    : "/jcloud/api/media/metadata/" + metadata.getId() + "/poster");
            result.add(vo);
        }
        result.sort(Comparator.comparing(MediaSeriesVo::getLastPlayTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
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
    public List<MediaItemVo> listOthers(String userId) {
        List<MediaItem> items = queryItems(userId, MediaItemType.OTHER.getCode());
        return toItemVos(items, false);
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
        List<MediaItem> items = mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                .eq(MediaItem::getUserId, userId)
                .eq(MediaItem::getItemType, MediaItemType.EPISODE.getCode())
                .eq(MediaItem::getSeriesName, seriesName));
        for (MediaItem item : items) {
            item.setMetadataId(metadata.getId());
            item.setMatchStatus(MediaMatchStatus.MANUAL.getCode());
            mediaItemMapper.updateById(item);
        }
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
        MediaItemVo first = episodes.stream()
                .filter(e -> e.getMetadataId() != null).findFirst().orElse(episodes.getFirst());
        MediaMetadata metadata = first.getMetadataId() == null ? null
                : mediaMetadataMapper.selectById(first.getMetadataId());

        MediaSeriesDetailVo vo = new MediaSeriesDetailVo();
        vo.setSeriesName(seriesName);
        vo.setMatchStatus(first.getMatchStatus());
        vo.setMetadataId(first.getMetadataId());
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

    private List<MediaItem> queryItems(String userId, String itemType) {
        return mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                .eq(MediaItem::getUserId, userId)
                .eq(MediaItem::getItemType, itemType)
                .orderByDesc(MediaItem::getLastPlayTime));
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
