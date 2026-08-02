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
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.MediaMatchUpdateDto;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.dto.MediaProgressUpdateDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesSeasonVo;
import com.fleyx.jcloud.model.vo.MediaSeriesVo;
import com.fleyx.jcloud.service.MediaItemService;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.MediaArtworkPersistSupport;
import com.fleyx.jcloud.service.support.MediaItemVoSupport;
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
    private final MediaSeasonMapper mediaSeasonMapper;
    private final FileMapper fileMapper;
    private final TmdbService tmdbService;
    private final MediaSeriesSupport mediaSeriesSupport;
    private final MediaItemVoSupport mediaItemVoSupport;
    private final MediaArtworkPersistSupport mediaArtworkPersistSupport;

    @Override
    public IPage<MediaItemVo> listMovies(String userId, MediaPageQueryDto query) {
        return queryItemPage(userId, MediaItemType.MOVIE.getCode(), query, true);
    }

    @Override
    public IPage<MediaSeriesVo> listSeries(String userId, MediaPageQueryDto query) {
        Page<MediaSeries> page = new Page<>(query.normalizedPageNum(), query.normalizedPageSize());
        IPage<MediaSeries> result = mediaSeriesMapper.selectSeriesPage(page, userId,
                blankToNull(query.getKeyword()), blankToNull(query.getDirectoryId()),
                query.sortByRelease() ? MediaPageQueryDto.SORT_FIELD_RELEASE : MediaPageQueryDto.SORT_FIELD_ADDED,
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
            vo.setId(series.getId());
            vo.setSeriesName(series.getSeriesName());
            vo.setEpisodeCount((long) episodes.size());
            vo.setMatchStatus(series.getMatchStatus());
            vo.setLastPlayTime(episodes.stream().map(MediaItem::getLastPlayTime)
                    .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null));
            vo.setMetadataId(series.getMetadataId());
            vo.setTitle(metadata != null ? metadata.getTitle() : series.getSeriesName());
            vo.setReleaseDate(metadata == null ? null : metadata.getReleaseDate());
            vo.setVoteAverage(metadata == null ? null : metadata.getVoteAverage());
            vo.setPosterUrl(mediaItemVoSupport.posterUrlOf(metadata));
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
    public List<MediaItemVo> listEpisodes(String seriesId, String userId) {
        List<MediaItem> items = mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                .eq(MediaItem::getUserId, userId)
                .eq(MediaItem::getItemType, MediaItemType.EPISODE.getCode())
                .eq(MediaItem::getSeriesId, seriesId));
        items.sort(Comparator.comparing(MediaItem::getSeasonNo, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(MediaItem::getEpisodeNo, Comparator.nullsLast(Integer::compareTo)));
        return mediaItemVoSupport.toItemVos(items, true);
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
                blankToNull(query.getKeyword()), blankToNull(query.getDirectoryId()),
                query.sortByRelease() ? MediaPageQueryDto.SORT_FIELD_RELEASE : MediaPageQueryDto.SORT_FIELD_ADDED,
                query.asc());
        Page<MediaItemVo> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(mediaItemVoSupport.toItemVos(result.getRecords(), withMetadata));
        return voPage;
    }

    private String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaItemVo updateMatch(String itemId, MediaMatchUpdateDto dto, String userId) {
        MediaItem item = requireOwned(itemId, userId);
        MediaMetadata metadata = tmdbService.getOrFetch(userId, dto.getTmdbId(), dto.getMediaType());
        item.setMetadataId(metadata.getId());
        item.setMatchStatus(MediaMatchStatus.MANUAL.getCode());
        mediaItemMapper.updateById(item);
        // 手动修正成功即写回视频目录的 NFO 与图片，落盘失败不影响匹配结果
        mediaArtworkPersistSupport.persistItem(item, metadata);
        return mediaItemVoSupport.toItemVos(List.of(item), true).getFirst();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSeriesMatch(String seriesName, MediaMatchUpdateDto dto, String userId) {
        MediaMetadata metadata = tmdbService.getOrFetch(userId, dto.getTmdbId(), "tv");
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
        // 手动修正成功即写回视频目录的 NFO 与图片，落盘失败不影响匹配结果
        mediaArtworkPersistSupport.persistSeries(series, metadata);
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
        vo.setSeriesId(item.getSeriesId());
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
            vo.setPosterUrl(mediaItemVoSupport.posterUrlOf(metadata));
            vo.setBackdropUrl(mediaItemVoSupport.backdropUrlOf(metadata));
        }
        if (vo.getTitle() == null) {
            vo.setTitle(vo.getFileName());
        }
        return vo;
    }

    @Override
    public MediaSeriesDetailVo getSeriesDetail(String seriesId, String userId) {
        MediaSeries series = mediaSeriesMapper.selectById(seriesId);
        if (series == null || !userId.equals(series.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "电视剧不存在");
        }
        MediaMetadata metadata = series.getMetadataId() == null ? null
                : mediaMetadataMapper.selectById(series.getMetadataId());

        MediaSeriesDetailVo vo = new MediaSeriesDetailVo();
        vo.setSeriesName(series.getSeriesName());
        vo.setMatchStatus(series.getMatchStatus());
        vo.setMetadataId(series.getMetadataId());
        vo.setSeasons(buildSeasonVos(series, userId));
        if (metadata != null) {
            vo.setTitle(metadata.getTitle());
            vo.setOriginalTitle(metadata.getOriginalTitle());
            vo.setOverview(metadata.getOverview());
            vo.setGenres(splitGenres(metadata.getGenres()));
            vo.setReleaseDate(metadata.getReleaseDate());
            vo.setVoteAverage(metadata.getVoteAverage());
            vo.setSeasonCount(metadata.getSeasonCount());
            vo.setPosterUrl(mediaItemVoSupport.posterUrlOf(metadata));
            vo.setBackdropUrl(mediaItemVoSupport.backdropUrlOf(metadata));
        }
        if (vo.getTitle() == null) {
            vo.setTitle(series.getSeriesName());
        }
        return vo;
    }

    @Override
    public List<MediaItemVo> listSeasonEpisodes(String seriesId, String seasonId, String userId) {
        MediaSeries series = mediaSeriesMapper.selectById(seriesId);
        if (series == null || !userId.equals(series.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "电视剧不存在");
        }
        MediaSeason season = mediaSeasonMapper.selectById(seasonId);
        if (season == null || !series.getId().equals(season.getSeriesId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "季不存在");
        }
        List<MediaItem> items = mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                .eq(MediaItem::getUserId, userId)
                .eq(MediaItem::getItemType, MediaItemType.EPISODE.getCode())
                .eq(MediaItem::getSeasonId, seasonId));
        items.sort(Comparator.comparing(MediaItem::getEpisodeNo, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(MediaItem::getId));
        return mediaItemVoSupport.toItemVos(items, true);
    }

    @Override
    public String getItemIdByFileNodeId(String fileNodeId, String userId) {
        MediaItem item = mediaItemMapper.selectOne(new LambdaQueryWrapper<MediaItem>()
                .eq(MediaItem::getFileNodeId, fileNodeId)
                .eq(MediaItem::getUserId, userId));
        if (item == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
        }
        return item.getId();
    }

    /**
     * 组装剧详情页的季卡片列表：按季号升序（未知季排最后），含集数与观看进度标记。
     */
    private List<MediaSeriesSeasonVo> buildSeasonVos(MediaSeries series, String userId) {
        List<MediaSeason> seasons = mediaSeasonMapper.selectList(
                new LambdaQueryWrapper<MediaSeason>().eq(MediaSeason::getSeriesId, series.getId()));
        if (seasons.isEmpty()) {
            return List.of();
        }
        Map<String, MediaMetadata> metadataMap = loadMetadataMapByIds(
                seasons.stream().map(MediaSeason::getMetadataId).toList());
        Map<String, List<MediaItem>> episodeMap = mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                        .eq(MediaItem::getUserId, userId)
                        .eq(MediaItem::getSeriesId, series.getId())
                        .select(MediaItem::getId, MediaItem::getSeasonId, MediaItem::getProgressMs))
                .stream().filter(i -> i.getSeasonId() != null)
                .collect(Collectors.groupingBy(MediaItem::getSeasonId));
        List<MediaSeriesSeasonVo> result = new ArrayList<>();
        for (MediaSeason season : seasons) {
            List<MediaItem> episodes = episodeMap.getOrDefault(season.getId(), List.of());
            MediaSeriesSeasonVo vo = new MediaSeriesSeasonVo();
            vo.setSeasonId(season.getId());
            vo.setSeasonNo(season.getSeasonNo());
            vo.setPosterUrl(season.getMetadataId() == null ? null
                    : mediaItemVoSupport.posterUrlOf(metadataMap.get(season.getMetadataId())));
            vo.setEpisodeCount((long) episodes.size());
            vo.setHasProgress(episodes.stream().anyMatch(e -> e.getProgressMs() != null && e.getProgressMs() > 0));
            result.add(vo);
        }
        result.sort(Comparator.comparing(MediaSeriesSeasonVo::getSeasonNo, Comparator.nullsLast(Integer::compareTo)));
        return result;
    }

    private List<String> splitGenres(String genres) {
        if (genres == null || genres.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(genres.split(",")).filter(g -> !g.isBlank()).toList();
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
}
