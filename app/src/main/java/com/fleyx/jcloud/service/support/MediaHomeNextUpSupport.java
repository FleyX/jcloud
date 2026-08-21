package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 影视首页「接下来」分区查询支撑组件（票据 06 从 MediaHomeQuerySupport 拆分）。
 * <p>
 * 每部有观看记录的剧取第一集未观看集（在播集归入继续观看，一律排除），
 * 按剧最近播放时间倒序。时长等文件事实取自代表文件明细（last_play_file_id 优先，缺省取最早）。
 */
@Component
@RequiredArgsConstructor
public class MediaHomeNextUpSupport {

    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaHomeItemSupport itemSupport;
    private final MediaHomeContinueWatchingSupport continueWatchingSupport;

    private record SeriesNextUp(MediaHomeItemSupport.HomeItem item, LocalDateTime lastPlayTime) {
    }

    /**
     * 接下来：每部有观看记录的剧取第一集未观看集，按剧最近播放时间倒序，截取 limit 条。
     */
    public List<MediaHomeItemSupport.HomeItem> listNextUp(String userId, int limit) {
        List<MediaSeries> seriesList = mediaSeriesMapper.selectList(
                new LambdaQueryWrapper<MediaSeries>().eq(MediaSeries::getUserId, userId));
        if (seriesList.isEmpty()) {
            return List.of();
        }
        List<String> seriesIds = seriesList.stream().map(MediaSeries::getId).toList();
        List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(
                new LambdaQueryWrapper<MediaEpisode>().in(MediaEpisode::getSeriesId, seriesIds));
        if (episodes.isEmpty()) {
            return List.of();
        }
        Map<String, MediaEpisodeFile> fileMap = itemSupport.representativeFiles(episodes);
        Map<String, MediaSeason> seasonMap = mediaSeasonMapper.selectBatchIds(
                        episodes.stream().map(MediaEpisode::getSeasonId).distinct().toList())
                .stream().collect(Collectors.toMap(MediaSeason::getId, Function.identity()));
        Map<String, MediaSeries> seriesById = seriesList.stream()
                .collect(Collectors.toMap(MediaSeries::getId, Function.identity()));
        Map<String, String> fileNameMap = itemSupport.loadFileNameMap(
                fileMap.values().stream().map(MediaEpisodeFile::getFileNodeId).toList());
        Map<String, List<MediaEpisode>> bySeries = episodes.stream()
                .collect(Collectors.groupingBy(MediaEpisode::getSeriesId));
        List<SeriesNextUp> candidates = new ArrayList<>();
        for (Map.Entry<String, List<MediaEpisode>> entry : bySeries.entrySet()) {
            MediaSeries series = seriesById.get(entry.getKey());
            if (series == null) {
                continue;
            }
            List<MediaEpisode> seriesEpisodes = entry.getValue();
            boolean hasHistory = seriesEpisodes.stream()
                    .anyMatch(e -> itemSupport.progressOf(e) > 0 || e.getLastPlayTime() != null);
            if (!hasHistory) {
                continue;
            }
            seriesEpisodes.sort(itemSupport.seasonEpisodeOrder(seasonMap));
            seriesEpisodes.stream()
                    .filter(e -> !Boolean.TRUE.equals(e.getWatched()))
                    .filter(e -> itemSupport.progressOf(e) == 0
                            && !itemSupport.isFinished(fileMap.get(e.getId()), e))
                    .findFirst()
                    .ifPresent(pick -> candidates.add(new SeriesNextUp(
                            continueWatchingSupport.toItem(series, pick, fileMap.get(pick.getId()),
                                    seasonMap.get(pick.getSeasonId()), fileNameMap),
                            maxLastPlayTime(seriesEpisodes))));
        }
        candidates.sort(Comparator.comparing(SeriesNextUp::lastPlayTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return candidates.stream().limit(limit).map(SeriesNextUp::item).toList();
    }

    private LocalDateTime maxLastPlayTime(List<MediaEpisode> episodes) {
        return episodes.stream().map(MediaEpisode::getLastPlayTime)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }
}
