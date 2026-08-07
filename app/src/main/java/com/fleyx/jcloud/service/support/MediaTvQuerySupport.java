package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.enums.MediaFavoriteOwnerType;
import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesSeasonVo;
import com.fleyx.jcloud.model.vo.MediaSeriesVo;
import com.fleyx.jcloud.service.MediaFavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 电视库新模型查询支撑组件（ADR 0021 / issue #17）：海报墙与剧集详情查询走新表
 * （t_media_series / t_media_season / t_media_episode / t_media_episode_file / t_media_metadata），
 * 视图对象沿用现有 VO，保持响应结构兼容（前端适配在 issue #21）。
 * <p>
 * 集以集行为卡片单位：代表文件取 last_play_file_id 指向的明细，未播放时取最早一条明细；
 * 播放进度/最近播放时间取自集行，时长等文件事实取自代表文件明细。
 */
@Component
@RequiredArgsConstructor
public class MediaTvQuerySupport {

    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaEpisodeFileMapper mediaEpisodeFileMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final FileMapper fileMapper;
    private final MediaItemVoSupport mediaItemVoSupport;
    private final MediaFavoriteService mediaFavoriteService;

    /**
     * 电视海报墙：按剧聚合分页（新表），含集数、匹配状态与最近播放时间。
     */
    public IPage<MediaSeriesVo> listSeries(String userId, MediaPageQueryDto query) {
        Page<MediaSeries> page = new Page<>(query.normalizedPageNum(), query.normalizedPageSize());
        IPage<MediaSeries> result = mediaSeriesMapper.selectSeriesPage(page, userId,
                blankToNull(query.getKeyword()), blankToNull(query.getDirectoryId()),
                blankToNull(query.getGenre()),
                query.resolveSortField(), query.asc());
        List<MediaSeries> seriesList = result.getRecords();
        Map<String, MediaMetadata> metadataMap = loadMetadataMap(
                seriesList.stream().map(MediaSeries::getMetadataId).toList());
        Map<String, Long> nodeVersionMap = mediaItemVoSupport.loadNodeVersionMap(
                metadataMap.values().stream().map(MediaMetadata::getPosterFileNodeId).toList());
        Map<String, List<MediaEpisode>> episodeMap = seriesList.isEmpty() ? Map.of()
                : mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                                .in(MediaEpisode::getSeriesId,
                                        seriesList.stream().map(MediaSeries::getId).toList()))
                        .stream().collect(Collectors.groupingBy(MediaEpisode::getSeriesId));

        List<MediaSeriesVo> vos = new ArrayList<>();
        for (MediaSeries series : seriesList) {
            List<MediaEpisode> episodes = episodeMap.getOrDefault(series.getId(), List.of());
            MediaMetadata metadata = series.getMetadataId() == null ? null : metadataMap.get(series.getMetadataId());
            MediaSeriesVo vo = new MediaSeriesVo();
            vo.setId(series.getId());
            vo.setSeriesName(series.getSeriesName());
            vo.setEpisodeCount((long) episodes.size());
            vo.setMatchStatus(series.getMatchStatus());
            vo.setMetadataComplete(series.getMetadataComplete());
            vo.setLastPlayTime(episodes.stream().map(MediaEpisode::getLastPlayTime)
                    .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null));
            vo.setMetadataId(series.getMetadataId());
            vo.setTitle(metadata != null && metadata.getTitle() != null ? metadata.getTitle() : series.getSeriesName());
            vo.setReleaseDate(metadata == null ? null : metadata.getReleaseDate());
            vo.setVoteAverage(metadata == null ? null : metadata.getVoteAverage());
            vo.setPosterUrl(mediaItemVoSupport.posterUrlOf(metadata, nodeVersionMap));
            vos.add(vo);
        }
        // 当前用户收藏状态批量填充（ownerType=SERIES）
        Set<String> favoritedIds = mediaFavoriteService.listFavoritedOwnerIds(userId, MediaFavoriteOwnerType.SERIES,
                seriesList.stream().map(MediaSeries::getId).toList());
        for (MediaSeriesVo vo : vos) {
            vo.setFavorited(favoritedIds.contains(vo.getId()));
        }
        Page<MediaSeriesVo> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(vos);
        return voPage;
    }

    /**
     * 查询某部剧的全部集（按季号、集号升序）。
     */
    public List<MediaItemVo> listEpisodes(String seriesId, String userId) {
        MediaSeries series = requireOwnedSeries(seriesId, userId);
        List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(
                new LambdaQueryWrapper<MediaEpisode>().eq(MediaEpisode::getSeriesId, seriesId));
        return toEpisodeVos(series, episodes, userId);
    }

    /**
     * 查询某部剧指定季的集列表（按集号升序）。
     */
    public List<MediaItemVo> listSeasonEpisodes(String seriesId, String seasonId, String userId) {
        MediaSeries series = requireOwnedSeries(seriesId, userId);
        MediaSeason season = mediaSeasonMapper.selectById(seasonId);
        if (season == null || !series.getId().equals(season.getSeriesId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "季不存在");
        }
        List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                .eq(MediaEpisode::getSeasonId, seasonId));
        return toEpisodeVos(series, episodes, userId);
    }

    /**
     * 剧详情：剧行 + 元数据 + 季卡片列表（季号升序、未知季排最后，含集数与观看进度标记）。
     */
    public MediaSeriesDetailVo getSeriesDetail(String seriesId, String userId) {
        MediaSeries series = requireOwnedSeries(seriesId, userId);
        MediaMetadata metadata = series.getMetadataId() == null ? null
                : mediaMetadataMapper.selectById(series.getMetadataId());
        Map<String, Long> nodeVersionMap = metadata == null ? Map.of() : mediaItemVoSupport.loadNodeVersionMap(
                List.of(metadata.getPosterFileNodeId(), metadata.getBackdropFileNodeId()));

        MediaSeriesDetailVo vo = new MediaSeriesDetailVo();
        vo.setSeriesName(series.getSeriesName());
        vo.setMatchStatus(series.getMatchStatus());
        vo.setMetadataComplete(series.getMetadataComplete());
        vo.setMetadataId(series.getMetadataId());
        vo.setSeasons(buildSeasonVos(series));
        vo.setSeasonCount(vo.getSeasons().size());
        if (metadata != null) {
            vo.setTitle(metadata.getTitle());
            vo.setOriginalTitle(metadata.getOriginalTitle());
            vo.setOverview(metadata.getOverview());
            vo.setReleaseDate(metadata.getReleaseDate());
            vo.setVoteAverage(metadata.getVoteAverage());
            vo.setPosterUrl(mediaItemVoSupport.posterUrlOf(metadata, nodeVersionMap));
            vo.setBackdropUrl(mediaItemVoSupport.backdropUrlOf(metadata, nodeVersionMap));
        }
        if (vo.getTitle() == null) {
            vo.setTitle(series.getSeriesName());
        }
        // 新元数据表无类型标签字段（issue #20 削刮切换时对齐），保持空列表兼容
        vo.setGenres(List.of());
        // 当前用户收藏状态填充（ownerType=SERIES + 季卡片 SEASON）
        vo.setFavorited(mediaFavoriteService.listFavoritedOwnerIds(userId, MediaFavoriteOwnerType.SERIES,
                List.of(seriesId)).contains(seriesId));
        Set<String> favoritedSeasonIds = mediaFavoriteService.listFavoritedOwnerIds(userId,
                MediaFavoriteOwnerType.SEASON,
                vo.getSeasons().stream().map(MediaSeriesSeasonVo::getSeasonId).toList());
        for (MediaSeriesSeasonVo seasonVo : vo.getSeasons()) {
            seasonVo.setFavorited(favoritedSeasonIds.contains(seasonVo.getSeasonId()));
        }
        return vo;
    }

    /**
     * 集详情（新表回退路径）：集行 + 代表文件明细 + 集元数据。
     */
    public MediaItemDetailVo getEpisodeDetail(String episodeId, String userId) {
        MediaEpisode episode = mediaEpisodeMapper.selectById(episodeId);
        MediaSeries series = episode == null ? null : mediaSeriesMapper.selectById(episode.getSeriesId());
        if (series == null || !userId.equals(series.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
        }
        MediaSeason season = mediaSeasonMapper.selectById(episode.getSeasonId());
        MediaEpisodeFile file = representativeFile(List.of(episode)).get(episode.getId());
        FileNode node = file == null ? null : fileMapper.selectById(file.getFileNodeId());
        MediaMetadata metadata = episode.getMetadataId() == null ? null
                : mediaMetadataMapper.selectById(episode.getMetadataId());
        Map<String, Long> nodeVersionMap = metadata == null ? Map.of() : mediaItemVoSupport.loadNodeVersionMap(
                List.of(metadata.getPosterFileNodeId(), metadata.getBackdropFileNodeId()));

        MediaItemDetailVo vo = new MediaItemDetailVo();
        vo.setId(episode.getId());
        vo.setItemType(MediaItemType.EPISODE.getCode());
        vo.setFileName(node == null ? null : node.getName());
        vo.setFileSize(node == null ? null : node.getSize());
        vo.setMetadataId(episode.getMetadataId());
        vo.setSeriesId(series.getId());
        vo.setSeriesName(series.getSeriesName());
        vo.setSeriesMetadataId(series.getMetadataId());
        vo.setSeasonNo(season == null ? null : season.getSeasonNo());
        vo.setEpisodeNo(episode.getEpisodeNo());
        vo.setDurationMs(file == null ? null : file.getDurationMs());
        vo.setProgressMs(episode.getProgressMs());
        if (file != null) {
            vo.setWidth(file.getWidth());
            vo.setHeight(file.getHeight());
            vo.setVideoCodec(file.getVideoCodec());
            vo.setAudioCodec(file.getAudioCodec());
        }
        if (metadata != null) {
            vo.setTitle(metadata.getTitle());
            vo.setOriginalTitle(metadata.getOriginalTitle());
            vo.setOverview(metadata.getOverview());
            vo.setReleaseDate(metadata.getReleaseDate());
            vo.setVoteAverage(metadata.getVoteAverage());
            vo.setPosterUrl(mediaItemVoSupport.posterUrlOf(metadata, nodeVersionMap));
            vo.setBackdropUrl(mediaItemVoSupport.backdropUrlOf(metadata, nodeVersionMap));
        }
        if (vo.getTitle() == null) {
            vo.setTitle(vo.getFileName());
        }
        vo.setGenres(List.of());
        return vo;
    }

    /**
     * 集列表转视图：按季号、集号升序；代表文件定位文件事实，进度取自集行；
     * 末尾按当前用户批量填充收藏状态（ownerType=EPISODE）。
     */
    private List<MediaItemVo> toEpisodeVos(MediaSeries series, List<MediaEpisode> episodes, String userId) {
        if (episodes.isEmpty()) {
            return List.of();
        }
        Map<String, MediaSeason> seasonMap = mediaSeasonMapper.selectBatchIds(
                        episodes.stream().map(MediaEpisode::getSeasonId).distinct().toList())
                .stream().collect(Collectors.toMap(MediaSeason::getId, Function.identity()));
        Map<String, MediaEpisodeFile> fileMap = representativeFile(episodes);
        Map<String, String> fileNameMap = loadFileNameMap(fileMap);
        Map<String, MediaMetadata> metadataMap = loadMetadataMap(
                episodes.stream().map(MediaEpisode::getMetadataId).toList());
        Map<String, Long> nodeVersionMap = mediaItemVoSupport.loadNodeVersionMap(
                metadataMap.values().stream().map(MediaMetadata::getPosterFileNodeId).toList());

        List<MediaEpisode> sorted = new ArrayList<>(episodes);
        sorted.sort(Comparator
                .comparing((MediaEpisode e) -> seasonMap.get(e.getSeasonId()) == null
                        ? null : seasonMap.get(e.getSeasonId()).getSeasonNo(),
                        Comparator.nullsLast(Integer::compareTo))
                .thenComparing(MediaEpisode::getEpisodeNo, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(MediaEpisode::getId));
        List<MediaItemVo> result = new ArrayList<>();
        for (MediaEpisode episode : sorted) {
            MediaEpisodeFile file = fileMap.get(episode.getId());
            MediaSeason season = seasonMap.get(episode.getSeasonId());
            MediaMetadata metadata = episode.getMetadataId() == null ? null : metadataMap.get(episode.getMetadataId());
            MediaItemVo vo = new MediaItemVo();
            vo.setId(episode.getId());
            vo.setFileNodeId(file == null ? null : file.getFileNodeId());
            vo.setItemType(MediaItemType.EPISODE.getCode());
            vo.setFileName(file == null ? null : fileNameMap.get(file.getFileNodeId()));
            vo.setMetadataId(episode.getMetadataId());
            vo.setSeriesId(series.getId());
            vo.setSeriesName(series.getSeriesName());
            vo.setSeasonNo(season == null ? null : season.getSeasonNo());
            vo.setEpisodeNo(episode.getEpisodeNo());
            vo.setDurationMs(file == null ? null : file.getDurationMs());
            vo.setProgressMs(episode.getProgressMs());
            vo.setLastPlayTime(episode.getLastPlayTime());
            if (metadata != null) {
                vo.setTitle(metadata.getTitle());
                vo.setReleaseDate(metadata.getReleaseDate());
                vo.setVoteAverage(metadata.getVoteAverage());
                vo.setPosterUrl(mediaItemVoSupport.posterUrlOf(metadata, nodeVersionMap));
            }
            if (vo.getTitle() == null) {
                vo.setTitle(vo.getFileName());
            }
            result.add(vo);
        }
        // 当前用户收藏状态批量填充（ownerType=EPISODE）
        Set<String> favoritedIds = mediaFavoriteService.listFavoritedOwnerIds(userId, MediaFavoriteOwnerType.EPISODE,
                result.stream().map(MediaItemVo::getId).toList());
        for (MediaItemVo vo : result) {
            vo.setFavorited(favoritedIds.contains(vo.getId()));
        }
        return result;
    }

    /**
     * 组装季卡片列表：按季号升序（未知季排最后），含集数与观看进度标记。
     */
    private List<MediaSeriesSeasonVo> buildSeasonVos(MediaSeries series) {
        List<MediaSeason> seasons = mediaSeasonMapper.selectList(
                new LambdaQueryWrapper<MediaSeason>().eq(MediaSeason::getSeriesId, series.getId()));
        if (seasons.isEmpty()) {
            return List.of();
        }
        Map<String, MediaMetadata> metadataMap = loadMetadataMap(
                seasons.stream().map(MediaSeason::getMetadataId).toList());
        Map<String, Long> nodeVersionMap = mediaItemVoSupport.loadNodeVersionMap(
                metadataMap.values().stream().map(MediaMetadata::getPosterFileNodeId).toList());
        Map<String, List<MediaEpisode>> episodeMap = mediaEpisodeMapper.selectList(
                        new LambdaQueryWrapper<MediaEpisode>().eq(MediaEpisode::getSeriesId, series.getId()))
                .stream().collect(Collectors.groupingBy(MediaEpisode::getSeasonId));
        List<MediaSeriesSeasonVo> result = new ArrayList<>();
        for (MediaSeason season : seasons) {
            List<MediaEpisode> episodes = episodeMap.getOrDefault(season.getId(), List.of());
            MediaSeriesSeasonVo vo = new MediaSeriesSeasonVo();
            vo.setSeasonId(season.getId());
            vo.setSeasonNo(season.getSeasonNo());
            vo.setPosterUrl(season.getMetadataId() == null ? null
                    : mediaItemVoSupport.posterUrlOf(metadataMap.get(season.getMetadataId()), nodeVersionMap));
            vo.setEpisodeCount((long) episodes.size());
            vo.setHasProgress(episodes.stream().anyMatch(e -> e.getProgressMs() != null && e.getProgressMs() > 0));
            result.add(vo);
        }
        result.sort(Comparator.comparing(MediaSeriesSeasonVo::getSeasonNo, Comparator.nullsLast(Integer::compareTo)));
        return result;
    }

    /**
     * 各集的代表文件明细：last_play_file_id 优先，否则取最早一条明细。
     */
    private Map<String, MediaEpisodeFile> representativeFile(List<MediaEpisode> episodes) {
        List<String> episodeIds = episodes.stream().map(MediaEpisode::getId).toList();
        Map<String, List<MediaEpisodeFile>> filesByEpisode = mediaEpisodeFileMapper.selectList(
                        new LambdaQueryWrapper<MediaEpisodeFile>().in(MediaEpisodeFile::getEpisodeId, episodeIds))
                .stream().collect(Collectors.groupingBy(MediaEpisodeFile::getEpisodeId));
        Map<String, MediaEpisodeFile> result = new java.util.HashMap<>();
        for (MediaEpisode episode : episodes) {
            List<MediaEpisodeFile> files = filesByEpisode.getOrDefault(episode.getId(), List.of());
            if (files.isEmpty()) {
                continue;
            }
            MediaEpisodeFile chosen = files.stream()
                    .filter(f -> f.getId().equals(episode.getLastPlayFileId())).findFirst()
                    .orElseGet(() -> files.stream()
                            .min(Comparator.comparing(MediaEpisodeFile::getCreateTime,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                                    .thenComparing(MediaEpisodeFile::getId))
                            .orElse(files.getFirst()));
            result.put(episode.getId(), chosen);
        }
        return result;
    }

    private MediaSeries requireOwnedSeries(String seriesId, String userId) {
        MediaSeries series = mediaSeriesMapper.selectById(seriesId);
        if (series == null || !userId.equals(series.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "电视剧不存在");
        }
        return series;
    }

    private Map<String, MediaMetadata> loadMetadataMap(List<String> metadataIds) {
        List<String> ids = metadataIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return mediaMetadataMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(MediaMetadata::getId, Function.identity()));
    }

    private Map<String, String> loadFileNameMap(Map<String, MediaEpisodeFile> fileMap) {
        List<String> fileNodeIds = fileMap.values().stream().map(MediaEpisodeFile::getFileNodeId)
                .filter(Objects::nonNull).distinct().toList();
        if (fileNodeIds.isEmpty()) {
            return Map.of();
        }
        return fileMapper.selectBatchIds(fileNodeIds).stream()
                .collect(Collectors.toMap(FileNode::getId, FileNode::getName));
    }

    private String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
