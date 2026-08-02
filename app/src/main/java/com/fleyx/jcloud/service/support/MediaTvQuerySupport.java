package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMetadataV2Mapper;
import com.fleyx.jcloud.mapper.MediaSeasonV2Mapper;
import com.fleyx.jcloud.mapper.MediaSeriesV2Mapper;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMetadataV2;
import com.fleyx.jcloud.model.po.MediaSeasonV2;
import com.fleyx.jcloud.model.po.MediaSeriesV2;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesSeasonVo;
import com.fleyx.jcloud.model.vo.MediaSeriesVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 电视库新模型查询支撑组件（ADR 0021 / issue #17）：海报墙与剧集详情查询走新表
 * （t_media_series_v2 / t_media_season_v2 / t_media_episode / t_media_episode_file / t_media_metadata_v2），
 * 视图对象沿用现有 VO，保持响应结构兼容（前端适配在 issue #21）。
 * <p>
 * 集以集行为卡片单位：代表文件取 last_play_file_id 指向的明细，未播放时取最早一条明细；
 * 播放进度/最近播放时间取自集行，时长等文件事实取自代表文件明细。
 */
@Component
@RequiredArgsConstructor
public class MediaTvQuerySupport {

    private final MediaSeriesV2Mapper mediaSeriesV2Mapper;
    private final MediaSeasonV2Mapper mediaSeasonV2Mapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaEpisodeFileMapper mediaEpisodeFileMapper;
    private final MediaMetadataV2Mapper mediaMetadataV2Mapper;
    private final FileMapper fileMapper;
    private final MediaItemVoSupport mediaItemVoSupport;

    /**
     * 电视海报墙：按剧聚合分页（新表），含集数、匹配状态与最近播放时间。
     */
    public IPage<MediaSeriesVo> listSeries(String userId, MediaPageQueryDto query) {
        Page<MediaSeriesV2> page = new Page<>(query.normalizedPageNum(), query.normalizedPageSize());
        IPage<MediaSeriesV2> result = mediaSeriesV2Mapper.selectSeriesPage(page, userId,
                blankToNull(query.getKeyword()), blankToNull(query.getDirectoryId()),
                query.sortByRelease() ? MediaPageQueryDto.SORT_FIELD_RELEASE : MediaPageQueryDto.SORT_FIELD_ADDED,
                query.asc());
        List<MediaSeriesV2> seriesList = result.getRecords();
        Map<String, MediaMetadataV2> metadataMap = loadMetadataMap(
                seriesList.stream().map(MediaSeriesV2::getMetadataId).toList());
        Map<String, List<MediaEpisode>> episodeMap = seriesList.isEmpty() ? Map.of()
                : mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                                .in(MediaEpisode::getSeriesId,
                                        seriesList.stream().map(MediaSeriesV2::getId).toList()))
                        .stream().collect(Collectors.groupingBy(MediaEpisode::getSeriesId));

        List<MediaSeriesVo> vos = new ArrayList<>();
        for (MediaSeriesV2 series : seriesList) {
            List<MediaEpisode> episodes = episodeMap.getOrDefault(series.getId(), List.of());
            MediaMetadataV2 metadata = series.getMetadataId() == null ? null : metadataMap.get(series.getMetadataId());
            MediaSeriesVo vo = new MediaSeriesVo();
            vo.setId(series.getId());
            vo.setSeriesName(series.getSeriesName());
            vo.setEpisodeCount((long) episodes.size());
            vo.setMatchStatus(series.getMatchStatus());
            vo.setLastPlayTime(episodes.stream().map(MediaEpisode::getLastPlayTime)
                    .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null));
            vo.setMetadataId(series.getMetadataId());
            vo.setTitle(metadata != null && metadata.getTitle() != null ? metadata.getTitle() : series.getSeriesName());
            vo.setReleaseDate(metadata == null ? null : metadata.getReleaseDate());
            vo.setVoteAverage(metadata == null ? null : metadata.getVoteAverage());
            vo.setPosterUrl(posterUrlOf(metadata));
            vos.add(vo);
        }
        Page<MediaSeriesVo> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(vos);
        return voPage;
    }

    /**
     * 查询某部剧的全部集（按季号、集号升序）。
     */
    public List<MediaItemVo> listEpisodes(String seriesId, String userId) {
        MediaSeriesV2 series = requireOwnedSeries(seriesId, userId);
        List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(
                new LambdaQueryWrapper<MediaEpisode>().eq(MediaEpisode::getSeriesId, seriesId));
        return toEpisodeVos(series, episodes);
    }

    /**
     * 查询某部剧指定季的集列表（按集号升序）。
     */
    public List<MediaItemVo> listSeasonEpisodes(String seriesId, String seasonId, String userId) {
        MediaSeriesV2 series = requireOwnedSeries(seriesId, userId);
        MediaSeasonV2 season = mediaSeasonV2Mapper.selectById(seasonId);
        if (season == null || !series.getId().equals(season.getSeriesId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "季不存在");
        }
        List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                .eq(MediaEpisode::getSeasonId, seasonId));
        return toEpisodeVos(series, episodes);
    }

    /**
     * 剧详情：剧行 + 元数据 + 季卡片列表（季号升序、未知季排最后，含集数与观看进度标记）。
     */
    public MediaSeriesDetailVo getSeriesDetail(String seriesId, String userId) {
        MediaSeriesV2 series = requireOwnedSeries(seriesId, userId);
        MediaMetadataV2 metadata = series.getMetadataId() == null ? null
                : mediaMetadataV2Mapper.selectById(series.getMetadataId());

        MediaSeriesDetailVo vo = new MediaSeriesDetailVo();
        vo.setSeriesName(series.getSeriesName());
        vo.setMatchStatus(series.getMatchStatus());
        vo.setMetadataId(series.getMetadataId());
        vo.setSeasons(buildSeasonVos(series));
        vo.setSeasonCount(vo.getSeasons().size());
        if (metadata != null) {
            vo.setTitle(metadata.getTitle());
            vo.setOriginalTitle(metadata.getOriginalTitle());
            vo.setOverview(metadata.getOverview());
            vo.setReleaseDate(metadata.getReleaseDate());
            vo.setVoteAverage(metadata.getVoteAverage());
            vo.setPosterUrl(posterUrlOf(metadata));
            vo.setBackdropUrl(backdropUrlOf(metadata));
        }
        if (vo.getTitle() == null) {
            vo.setTitle(series.getSeriesName());
        }
        // 新元数据表无类型标签字段（issue #20 削刮切换时对齐），保持空列表兼容
        vo.setGenres(List.of());
        return vo;
    }

    /**
     * 集详情（新表回退路径）：集行 + 代表文件明细 + 集元数据。
     */
    public MediaItemDetailVo getEpisodeDetail(String episodeId, String userId) {
        MediaEpisode episode = mediaEpisodeMapper.selectById(episodeId);
        MediaSeriesV2 series = episode == null ? null : mediaSeriesV2Mapper.selectById(episode.getSeriesId());
        if (series == null || !userId.equals(series.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
        }
        MediaSeasonV2 season = mediaSeasonV2Mapper.selectById(episode.getSeasonId());
        MediaEpisodeFile file = representativeFile(List.of(episode)).get(episode.getId());
        FileNode node = file == null ? null : fileMapper.selectById(file.getFileNodeId());
        MediaMetadataV2 metadata = episode.getMetadataId() == null ? null
                : mediaMetadataV2Mapper.selectById(episode.getMetadataId());

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
            vo.setPosterUrl(posterUrlOf(metadata));
            vo.setBackdropUrl(backdropUrlOf(metadata));
        }
        if (vo.getTitle() == null) {
            vo.setTitle(vo.getFileName());
        }
        vo.setGenres(List.of());
        return vo;
    }

    /**
     * 集列表转视图：按季号、集号升序；代表文件定位文件事实，进度取自集行。
     */
    private List<MediaItemVo> toEpisodeVos(MediaSeriesV2 series, List<MediaEpisode> episodes) {
        if (episodes.isEmpty()) {
            return List.of();
        }
        Map<String, MediaSeasonV2> seasonMap = mediaSeasonV2Mapper.selectBatchIds(
                        episodes.stream().map(MediaEpisode::getSeasonId).distinct().toList())
                .stream().collect(Collectors.toMap(MediaSeasonV2::getId, Function.identity()));
        Map<String, MediaEpisodeFile> fileMap = representativeFile(episodes);
        Map<String, String> fileNameMap = loadFileNameMap(fileMap);
        Map<String, MediaMetadataV2> metadataMap = loadMetadataMap(
                episodes.stream().map(MediaEpisode::getMetadataId).toList());

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
            MediaSeasonV2 season = seasonMap.get(episode.getSeasonId());
            MediaMetadataV2 metadata = episode.getMetadataId() == null ? null : metadataMap.get(episode.getMetadataId());
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
                vo.setPosterUrl(posterUrlOf(metadata));
            }
            if (vo.getTitle() == null) {
                vo.setTitle(vo.getFileName());
            }
            result.add(vo);
        }
        return result;
    }

    /**
     * 组装季卡片列表：按季号升序（未知季排最后），含集数与观看进度标记。
     */
    private List<MediaSeriesSeasonVo> buildSeasonVos(MediaSeriesV2 series) {
        List<MediaSeasonV2> seasons = mediaSeasonV2Mapper.selectList(
                new LambdaQueryWrapper<MediaSeasonV2>().eq(MediaSeasonV2::getSeriesId, series.getId()));
        if (seasons.isEmpty()) {
            return List.of();
        }
        Map<String, MediaMetadataV2> metadataMap = loadMetadataMap(
                seasons.stream().map(MediaSeasonV2::getMetadataId).toList());
        Map<String, List<MediaEpisode>> episodeMap = mediaEpisodeMapper.selectList(
                        new LambdaQueryWrapper<MediaEpisode>().eq(MediaEpisode::getSeriesId, series.getId()))
                .stream().collect(Collectors.groupingBy(MediaEpisode::getSeasonId));
        List<MediaSeriesSeasonVo> result = new ArrayList<>();
        for (MediaSeasonV2 season : seasons) {
            List<MediaEpisode> episodes = episodeMap.getOrDefault(season.getId(), List.of());
            MediaSeriesSeasonVo vo = new MediaSeriesSeasonVo();
            vo.setSeasonId(season.getId());
            vo.setSeasonNo(season.getSeasonNo());
            vo.setPosterUrl(season.getMetadataId() == null ? null
                    : posterUrlOf(metadataMap.get(season.getMetadataId())));
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

    private MediaSeriesV2 requireOwnedSeries(String seriesId, String userId) {
        MediaSeriesV2 series = mediaSeriesV2Mapper.selectById(seriesId);
        if (series == null || !userId.equals(series.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "电视剧不存在");
        }
        return series;
    }

    private Map<String, MediaMetadataV2> loadMetadataMap(List<String> metadataIds) {
        List<String> ids = metadataIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return mediaMetadataV2Mapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(MediaMetadataV2::getId, Function.identity()));
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

    /**
     * 元数据海报图 URL，无海报时返回 null。
     */
    private String posterUrlOf(MediaMetadataV2 metadata) {
        return metadata == null || metadata.getPosterFileNodeId() == null ? null
                : mediaItemVoSupport.metadataPosterUrl(metadata.getId());
    }

    /**
     * 元数据背景图 URL，无背景图时返回 null。
     */
    private String backdropUrlOf(MediaMetadataV2 metadata) {
        return metadata == null || metadata.getBackdropFileNodeId() == null ? null
                : mediaItemVoSupport.metadataBackdropUrl(metadata.getId());
    }

    private String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
