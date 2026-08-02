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
import com.fleyx.jcloud.service.support.MediaArtworkPersistSupport;
import com.fleyx.jcloud.service.support.MediaItemVoSupport;
import com.fleyx.jcloud.service.support.MediaSeriesSupport;
import com.fleyx.jcloud.service.support.MediaTvQuerySupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 媒体条目查询与匹配服务实现。
 * <p>
 * 电影/其他条目仍走旧表；电视海报墙与剧集详情已切换到新模型表（issue #17），
 * 委托 {@link MediaTvQuerySupport}。
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
    private final MediaItemVoSupport mediaItemVoSupport;
    private final MediaArtworkPersistSupport mediaArtworkPersistSupport;
    private final MediaTvQuerySupport mediaTvQuerySupport;

    @Override
    public IPage<MediaItemVo> listMovies(String userId, MediaPageQueryDto query) {
        return queryItemPage(userId, MediaItemType.MOVIE.getCode(), query, true);
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
        MediaItem item = mediaItemMapper.selectById(itemId);
        if (item == null) {
            // 电视库新模型的集详情（issue #17）
            return mediaTvQuerySupport.getEpisodeDetail(itemId, userId);
        }
        if (!userId.equals(item.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
        }
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
        return mediaTvQuerySupport.getSeriesDetail(seriesId, userId);
    }

    @Override
    public List<MediaItemVo> listSeasonEpisodes(String seriesId, String seasonId, String userId) {
        return mediaTvQuerySupport.listSeasonEpisodes(seriesId, seasonId, userId);
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

    private List<String> splitGenres(String genres) {
        if (genres == null || genres.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(genres.split(",")).filter(g -> !g.isBlank()).toList();
    }

    private MediaItem requireOwned(String itemId, String userId) {
        MediaItem item = mediaItemMapper.selectById(itemId);
        if (item == null || !userId.equals(item.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
        }
        return item;
    }
}
