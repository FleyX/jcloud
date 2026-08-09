package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaSeries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 削刮候选探测支撑组件（工单 05/07）：批量收集电影/剧集的 NFO 缺失与背景图产物缺失映射，
 * 供整库削刮循环判定候选行使用。全部为批量查询（一次 IN parent_id / selectBatchIds），
 * 避免逐行探测的 N+1。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaArtifactProbeSupport {

    private final MediaMovieFileMapper mediaMovieFileMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaEpisodeFileMapper mediaEpisodeFileMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final FileMapper fileMapper;
    private final MediaNfoSupport mediaNfoSupport;
    private final ObjectMapper objectMapper;

    /**
     * 批量收集各电影 NFO 是否缺失（一次 IN parent_id 查询）：电影文件夹下 {@code movie.nfo}
     * 与视频同名 {@code .nfo} 均不存在才算缺失（识别链两个名字都没有）。
     *
     * @return movieId → nfoMissing
     */
    public Map<String, Boolean> collectMovieNfoMissing(List<MediaMovie> movies) {
        if (movies.isEmpty()) {
            return Map.of();
        }
        List<String> movieIds = movies.stream().map(MediaMovie::getId).toList();
        Map<String, String> fileNodeIdByMovie = mediaMovieFileMapper.selectList(
                        new LambdaQueryWrapper<MediaMovieFile>().in(MediaMovieFile::getMovieId, movieIds))
                .stream().collect(Collectors.toMap(MediaMovieFile::getMovieId, MediaMovieFile::getFileNodeId, (a, b) -> a));
        Map<String, FileNode> fileById = loadFileById(fileNodeIdByMovie.values());
        List<String> parentIds = new ArrayList<>(movies.stream()
                .map(MediaMovie::getFolderNodeId).filter(Objects::nonNull).collect(Collectors.toSet()));
        fileById.values().stream().map(FileNode::getParentId).filter(Objects::nonNull).forEach(parentIds::add);
        Map<String, Set<String>> namesByParent = loadNamesByParent(parentIds);
        Map<String, Boolean> result = new HashMap<>();
        for (MediaMovie movie : movies) {
            Set<String> folderNames = namesByParent.getOrDefault(movie.getFolderNodeId(), Set.of());
            FileNode video = fileById.get(fileNodeIdByMovie.get(movie.getId()));
            String videoName = video == null ? null : video.getName();
            boolean hasNfo = folderNames.contains(MediaNfoSupport.MOVIE_NFO)
                    || (videoName != null && folderNames.contains(mediaNfoSupport.nfoNameOf(videoName)));
            result.put(movie.getId(), !hasNfo);
        }
        return result;
    }

    /**
     * 批量收集各剧集 NFO 是否缺失（一次 IN parent_id 查询）：剧文件夹无 {@code tvshow.nfo}，
     * 或任一集视频同目录无同名 {@code .nfo}（聚合到剧行）。
     *
     * @return seriesId → nfoMissing
     */
    public Map<String, Boolean> collectSeriesNfoMissing(List<MediaSeries> seriesList) {
        if (seriesList.isEmpty()) {
            return Map.of();
        }
        List<String> seriesIds = seriesList.stream().map(MediaSeries::getId).toList();
        List<MediaEpisode> allEpisodes = mediaEpisodeMapper.selectList(
                new LambdaQueryWrapper<MediaEpisode>().in(MediaEpisode::getSeriesId, seriesIds));
        List<String> episodeIds = allEpisodes.stream().map(MediaEpisode::getId).toList();
        Map<String, String> fileNodeIdByEpisode = episodeIds.isEmpty() ? Map.of()
                : mediaEpisodeFileMapper.selectList(
                                new LambdaQueryWrapper<MediaEpisodeFile>().in(MediaEpisodeFile::getEpisodeId, episodeIds))
                        .stream().collect(Collectors.toMap(MediaEpisodeFile::getEpisodeId, MediaEpisodeFile::getFileNodeId, (a, b) -> a));
        Map<String, FileNode> fileById = loadFileById(fileNodeIdByEpisode.values());
        List<String> parentIds = new ArrayList<>(seriesList.stream()
                .map(MediaSeries::getFolderNodeId).filter(Objects::nonNull).collect(Collectors.toSet()));
        fileById.values().stream().map(FileNode::getParentId).filter(Objects::nonNull).forEach(parentIds::add);
        Map<String, Set<String>> namesByParent = loadNamesByParent(parentIds);
        Map<String, Boolean> result = new HashMap<>();
        for (MediaSeries series : seriesList) {
            Set<String> folderNames = namesByParent.getOrDefault(series.getFolderNodeId(), Set.of());
            boolean hasTvshowNfo = folderNames.contains(MediaNfoSupport.TVSHOW_NFO);
            boolean allEpisodesHaveNfo = true;
            for (MediaEpisode episode : allEpisodes) {
                if (!series.getId().equals(episode.getSeriesId())) {
                    continue;
                }
                FileNode video = fileById.get(fileNodeIdByEpisode.get(episode.getId()));
                if (video == null) {
                    continue;
                }
                if (!namesByParent.getOrDefault(video.getParentId(), Set.of())
                        .contains(mediaNfoSupport.nfoNameOf(video.getName()))) {
                    allEpisodesHaveNfo = false;
                    break;
                }
            }
            result.put(series.getId(), !hasTvshowNfo || !allEpisodesHaveNfo);
        }
        return result;
    }

    /**
     * 批量收集各电影背景图产物是否缺失（工单 07）：判定逻辑见共享实现 {@link #collectBackdropMissing}。
     * 无元数据行的电影不判缺失（本就在削刮候选里）。
     *
     * @return movieId → backdropMissing
     */
    public Map<String, Boolean> collectMovieBackdropMissing(List<MediaMovie> movies) {
        return collectBackdropMissing(movies, MediaMovie::getId, MediaMovie::getMetadataId);
    }

    /**
     * 批量收集各剧集背景图产物是否缺失（工单 07）：判定逻辑见共享实现 {@link #collectBackdropMissing}，
     * 只看剧级元数据行（季海报/集剧照指针悬空已由完整性聚合覆盖，不重复探测）。
     *
     * @return seriesId → backdropMissing
     */
    public Map<String, Boolean> collectSeriesBackdropMissing(List<MediaSeries> seriesList) {
        return collectBackdropMissing(seriesList, MediaSeries::getId, MediaSeries::getMetadataId);
    }

    /**
     * 背景图产物缺失收集共享实现（工单 09）：按行类型访问器参数化的通用逻辑——按 metadataId 一次
     * selectBatchIds 加载元数据行，非空 backdropFileNodeId 合并做一次 IN 查询得存活集合；
     * 缺失判定见 {@link #backdropMissing}。无元数据行的行不判缺失（本就在削刮候选里）。
     *
     * @param idOf         行 ID 访问器（movieId/seriesId）
     * @param metadataIdOf 行元数据行 ID 访问器
     */
    private <Row> Map<String, Boolean> collectBackdropMissing(List<Row> rows,
                                                              Function<Row, String> idOf,
                                                              Function<Row, String> metadataIdOf) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, MediaMetadata> metadataById = loadMetadataById(rows.stream()
                .map(metadataIdOf).filter(Objects::nonNull).distinct().toList());
        Set<String> aliveNodeIds = loadFileById(metadataById.values().stream()
                .map(MediaMetadata::getBackdropFileNodeId).filter(Objects::nonNull).toList()).keySet();
        Map<String, Boolean> result = new HashMap<>();
        for (Row row : rows) {
            String metadataId = metadataIdOf.apply(row);
            result.put(idOf.apply(row), backdropMissing(
                    metadataId == null ? null : metadataById.get(metadataId), aliveNodeIds));
        }
        return result;
    }

    /**
     * 背景图缺失判定：指针非空且文件已不存在，或指针为空但 rawJson 含非空 backdrop_path。
     * 无元数据行视为不缺失（行本就在削刮候选里）。
     */
    private boolean backdropMissing(MediaMetadata metadata, Set<String> aliveNodeIds) {
        if (metadata == null) {
            return false;
        }
        if (metadata.getBackdropFileNodeId() != null) {
            return !aliveNodeIds.contains(metadata.getBackdropFileNodeId());
        }
        return backdropPathPresent(metadata.getRawJson());
    }

    /** 一次 selectBatchIds 加载元数据行（id → 行），入参已去空去重。 */
    private Map<String, MediaMetadata> loadMetadataById(List<String> metadataIds) {
        if (metadataIds.isEmpty()) {
            return Map.of();
        }
        return mediaMetadataMapper.selectBatchIds(metadataIds).stream()
                .collect(Collectors.toMap(MediaMetadata::getId, Function.identity()));
    }

    /** rawJson 是否含非空 backdrop_path（与 persist 通道取图字段一致，解析失败视为无）。 */
    private boolean backdropPathPresent(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return false;
        }
        try {
            JsonNode value = objectMapper.readTree(rawJson).path("backdrop_path");
            return value.isTextual() && !value.asText().isBlank();
        } catch (Exception e) {
            log.debug("元数据原始响应解析失败: {}", e.getMessage());
            return false;
        }
    }

    /** 一次 IN 查询加载文件节点（id → 节点）。 */
    private Map<String, FileNode> loadFileById(Collection<String> fileNodeIds) {
        List<String> ids = fileNodeIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return fileMapper.selectList(new LambdaQueryWrapper<FileNode>().in(FileNode::getId, ids))
                .stream().collect(Collectors.toMap(FileNode::getId, Function.identity()));
    }

    /** 一次 IN parent_id 查询加载各目录下子文件名集合（parentId → 子文件名）。 */
    private Map<String, Set<String>> loadNamesByParent(List<String> parentIds) {
        List<String> ids = parentIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return fileMapper.selectList(new LambdaQueryWrapper<FileNode>().in(FileNode::getParentId, ids))
                .stream().collect(Collectors.groupingBy(FileNode::getParentId,
                        Collectors.mapping(FileNode::getName, Collectors.toSet())));
    }
}
