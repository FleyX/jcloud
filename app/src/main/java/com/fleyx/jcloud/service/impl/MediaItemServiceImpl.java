package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.mapper.MediaSeriesV2Mapper;
import com.fleyx.jcloud.model.dto.MediaMatchUpdateDto;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.dto.MediaProgressUpdateDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.po.MediaSeriesV2;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesVo;
import com.fleyx.jcloud.service.MediaItemService;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.MediaArtworkPersistSupport;
import com.fleyx.jcloud.service.support.MediaItemVoSupport;
import com.fleyx.jcloud.service.support.MediaMovieQuerySupport;
import com.fleyx.jcloud.service.support.MediaOtherQuerySupport;
import com.fleyx.jcloud.service.support.MediaPlaybackResolveSupport;
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
 * 电视（issue #17）/电影（issue #18）/其他（issue #19）海报墙与详情均已切换到新模型表，
 * 分别委托 {@link MediaTvQuerySupport} / {@link MediaMovieQuerySupport} / {@link MediaOtherQuerySupport}；
 * 播放进度记录到标题级新行（电影/集/其他），续播通过 last_play_file_id 定位版本文件。
 */
@Service
@RequiredArgsConstructor
public class MediaItemServiceImpl implements MediaItemService {

    private final MediaItemMapper mediaItemMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaMovieMapper mediaMovieMapper;
    private final MediaMovieFileMapper mediaMovieFileMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaEpisodeFileMapper mediaEpisodeFileMapper;
    private final MediaOtherMapper mediaOtherMapper;
    private final MediaSeriesV2Mapper mediaSeriesV2Mapper;
    private final FileMapper fileMapper;
    private final TmdbService tmdbService;
    private final MediaSeriesSupport mediaSeriesSupport;
    private final MediaItemVoSupport mediaItemVoSupport;
    private final MediaArtworkPersistSupport mediaArtworkPersistSupport;
    private final MediaTvQuerySupport mediaTvQuerySupport;
    private final MediaMovieQuerySupport mediaMovieQuerySupport;
    private final MediaOtherQuerySupport mediaOtherQuerySupport;
    private final MediaPlaybackResolveSupport mediaPlaybackResolveSupport;

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
        // 播放进度记录到标题级新行（issue #19）：电影 → t_media_movie、集 → t_media_episode、
        // 其他 → t_media_other；一部电影多版本共享进度，续播按 last_play_file_id 定位版本文件
        LocalDateTime now = LocalDateTime.now();
        MediaMovie movie = mediaMovieMapper.selectById(itemId);
        if (movie != null) {
            if (!userId.equals(movie.getUserId())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
            }
            MediaMovieFile file = mediaPlaybackResolveSupport.pickMovieFile(movie);
            movie.setProgressMs(dto.getProgressMs());
            movie.setLastPlayTime(now);
            if (file != null) {
                movie.setLastPlayFileId(file.getId());
            }
            mediaMovieMapper.updateById(movie);
            return;
        }
        MediaEpisode episode = mediaEpisodeMapper.selectById(itemId);
        if (episode != null) {
            MediaSeriesV2 series = mediaSeriesV2Mapper.selectById(episode.getSeriesId());
            if (series == null || !userId.equals(series.getUserId())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
            }
            MediaEpisodeFile file = mediaPlaybackResolveSupport.pickEpisodeFile(episode);
            episode.setProgressMs(dto.getProgressMs());
            episode.setLastPlayTime(now);
            if (file != null) {
                episode.setLastPlayFileId(file.getId());
            }
            mediaEpisodeMapper.updateById(episode);
            return;
        }
        MediaOther other = mediaOtherMapper.selectById(itemId);
        if (other == null || !userId.equals(other.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
        }
        other.setProgressMs(dto.getProgressMs());
        other.setLastPlayTime(now);
        mediaOtherMapper.updateById(other);
    }

    @Override
    public MediaItemDetailVo getItemDetail(String itemId, String userId) {
        MediaItem item = mediaItemMapper.selectById(itemId);
        if (item != null) {
            if (!userId.equals(item.getUserId())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
            }
            return toLegacyItemDetail(item, userId);
        }
        // 新模型回退：电影详情（issue #18）、集详情（issue #17）、其他详情（issue #19）
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

    /**
     * 旧表条目详情组装（存量旧数据，issue #21 弃表后移除）。
     */
    private MediaItemDetailVo toLegacyItemDetail(MediaItem item, String userId) {
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
        // 新模型优先（issue #19）：其他行（文件级）→ 其他 ID；电影/集文件明细 → 标题级 ID
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
                MediaSeriesV2 series = mediaSeriesV2Mapper.selectById(episode.getSeriesId());
                if (series != null && userId.equals(series.getUserId())) {
                    return episode.getId();
                }
            }
        }
        // 旧表存量数据兜底（issue #21 弃表后移除）
        MediaItem item = mediaItemMapper.selectOne(new LambdaQueryWrapper<MediaItem>()
                .eq(MediaItem::getFileNodeId, fileNodeId)
                .eq(MediaItem::getUserId, userId));
        if (item != null) {
            return item.getId();
        }
        throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
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
