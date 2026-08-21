package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 影视首页「最新」分区查询支撑组件（票据 06 从 MediaHomeQuerySupport 拆分）。
 * <p>
 * 最新电影：按电影实体入库时间倒序，每部电影只返回一张卡片；
 * 最新剧集：按剧实体最近入库时间倒序，每部剧只返回一张聚合卡片，海报回退文件节点按剧内季/集顺序取第一个有代表文件的集。
 */
@Component
@RequiredArgsConstructor
public class MediaHomeLatestSupport {

    private final MediaMovieMapper mediaMovieMapper;
    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaHomeItemSupport itemSupport;

    /**
     * 最新电影：按电影实体入库时间倒序，每部电影只返回一张卡片。
     */
    public List<MediaHomeItemSupport.HomeItem> listLatestMovies(String userId, int limit) {
        List<MediaMovie> movies = mediaMovieMapper.selectList(new LambdaQueryWrapper<MediaMovie>()
                .eq(MediaMovie::getUserId, userId)
                .isNotNull(MediaMovie::getAddedTime)
                .orderByDesc(MediaMovie::getAddedTime)
                .orderByDesc(MediaMovie::getId)
                .last("LIMIT " + boundedLimit(limit)));
        if (movies.isEmpty()) {
            return List.of();
        }
        Map<String, MediaMovieFile> fileMap = itemSupport.representativeMovieFiles(movies);
        Map<String, String> fileNameMap = itemSupport.loadFileNameMap(
                fileMap.values().stream().map(MediaMovieFile::getFileNodeId).toList());
        return movies.stream().map(movie -> {
            MediaMovieFile file = fileMap.get(movie.getId());
            String fileNodeId = file == null ? null : file.getFileNodeId();
            return new MediaHomeItemSupport.HomeItem(movie.getId(), fileNodeId, MediaItemType.MOVIE.getCode(),
                    movie.getTitle(), fileNodeId == null ? null : fileNameMap.get(fileNodeId),
                    movie.getMetadataId(), null, null, null, null, null,
                    null, movie.getProgressMs(), movie.getWatched(), movie.getLastPlayTime(),
                    movie.getAddedTime(), null);
        }).toList();
    }

    /**
     * 最新剧集：按剧实体最近入库时间倒序，每部剧只返回一张聚合卡片。
     * 卡片无代表文件（fileNodeId 恒为 null），海报回退文件节点按剧内季/集顺序取第一个有代表文件的集。
     */
    public List<MediaHomeItemSupport.HomeItem> listLatestSeries(String userId, int limit) {
        List<MediaSeries> seriesList = mediaSeriesMapper.selectList(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getUserId, userId)
                .isNotNull(MediaSeries::getLatestAddedTime)
                .orderByDesc(MediaSeries::getLatestAddedTime)
                .orderByDesc(MediaSeries::getId)
                .last("LIMIT " + boundedLimit(limit)));
        if (seriesList.isEmpty()) {
            return List.of();
        }
        Map<String, String> posterFallbackBySeries = loadPosterFallbackFileNodes(seriesList);
        return seriesList.stream().map(series -> new MediaHomeItemSupport.HomeItem(
                series.getId(), null, MediaItemType.SERIES.getCode(), series.getSeriesName(), null,
                series.getMetadataId(), series.getId(), series.getSeriesName(), null,
                null, null, null, null, series.getWatched(), null, series.getLatestAddedTime(),
                posterFallbackBySeries.get(series.getId()))).toList();
    }

    /**
     * 各剧的海报回退文件节点：按季/集顺序取第一个有代表文件的集。
     * 仅用于最新剧集卡无海报时的文件预览兜底，不参与排序与时间计算。
     */
    private Map<String, String> loadPosterFallbackFileNodes(List<MediaSeries> seriesList) {
        List<String> seriesIds = seriesList.stream().map(MediaSeries::getId).toList();
        List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(
                new LambdaQueryWrapper<MediaEpisode>().in(MediaEpisode::getSeriesId, seriesIds));
        if (episodes.isEmpty()) {
            return Map.of();
        }
        Map<String, MediaEpisodeFile> fileMap = itemSupport.representativeFiles(episodes);
        Map<String, MediaSeason> seasonMap = mediaSeasonMapper.selectBatchIds(
                        episodes.stream().map(MediaEpisode::getSeasonId).distinct().toList())
                .stream().collect(Collectors.toMap(MediaSeason::getId, Function.identity()));
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, List<MediaEpisode>> entry : episodes.stream()
                .collect(Collectors.groupingBy(MediaEpisode::getSeriesId)).entrySet()) {
            List<MediaEpisode> seriesEpisodes = new ArrayList<>(entry.getValue());
            seriesEpisodes.sort(itemSupport.seasonEpisodeOrder(seasonMap));
            seriesEpisodes.stream()
                    .map(episode -> fileMap.get(episode.getId()))
                    .filter(Objects::nonNull)
                    .map(MediaEpisodeFile::getFileNodeId)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .ifPresent(fileNodeId -> result.put(entry.getKey(), fileNodeId));
        }
        return result;
    }

    private int boundedLimit(int limit) {
        return Math.max(0, Math.min(limit, 16));
    }
}
