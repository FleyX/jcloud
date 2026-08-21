package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 影视首页「继续观看」分区查询支撑组件（票据 06 从 MediaHomeQuerySupport 拆分）。
 * <p>
 * 电影/集/其他三类标题级行中「有进度且未看完」的条目（时长为空无法计算阈值时视为未看完），
 * 按最近播放时间倒序。时长等文件事实取自代表文件明细（last_play_file_id 优先，缺省取最早）。
 */
@Component
@RequiredArgsConstructor
public class MediaHomeContinueWatchingSupport {

    private final MediaMovieMapper mediaMovieMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaOtherMapper mediaOtherMapper;
    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaHomeItemSupport itemSupport;

    /**
     * 继续观看：三类标题级行中「有进度且未看完」的条目，按最近播放时间倒序，截取 limit 条。
     */
    public List<MediaHomeItemSupport.HomeItem> listContinueWatching(String userId, int limit) {
        List<MediaHomeItemSupport.HomeItem> items = new ArrayList<>();
        items.addAll(movieItems(userId, true));
        items.addAll(episodeItems(userId, true));
        items.addAll(otherItems(userId, true));
        return items.stream().filter(i -> !itemSupport.isFinished(i))
                .sorted(itemSupport.lastPlayDesc()).limit(limit).toList();
    }

    /**
     * 电影候选行：全部电影行（可选仅带进度），时长取代表文件明细。
     */
    private List<MediaHomeItemSupport.HomeItem> movieItems(String userId, boolean withProgressOnly) {
        List<MediaMovie> movies = mediaMovieMapper.selectList(
                new LambdaQueryWrapper<MediaMovie>()
                        .eq(MediaMovie::getUserId, userId)
                        .eq(MediaMovie::getWatched, false));
        if (withProgressOnly) {
            movies = movies.stream().filter(m -> itemSupport.progressOf(m) > 0).toList();
        }
        if (movies.isEmpty()) {
            return List.of();
        }
        Map<String, MediaMovieFile> fileMap = itemSupport.representativeMovieFiles(movies);
        Map<String, String> fileNameMap = itemSupport.loadFileNameMap(
                fileMap.values().stream().map(MediaMovieFile::getFileNodeId).toList());
        List<MediaHomeItemSupport.HomeItem> result = new ArrayList<>();
        for (MediaMovie movie : movies) {
            MediaMovieFile file = fileMap.get(movie.getId());
            if (file == null) {
                continue;
            }
            result.add(new MediaHomeItemSupport.HomeItem(movie.getId(), file.getFileNodeId(),
                    MediaItemType.MOVIE.getCode(), movie.getTitle(), fileNameMap.get(file.getFileNodeId()),
                    movie.getMetadataId(), null, null, null, null, null,
                    file.getDurationMs(), movie.getProgressMs(), movie.getWatched(), movie.getLastPlayTime(),
                    null, null));
        }
        return result;
    }

    /**
     * 集候选行：全部集行（可选仅带进度），时长取代表文件明细，剧名/季号/剧元数据补齐。
     */
    private List<MediaHomeItemSupport.HomeItem> episodeItems(String userId, boolean withProgressOnly) {
        List<MediaSeries> seriesList = mediaSeriesMapper.selectList(
                new LambdaQueryWrapper<MediaSeries>().eq(MediaSeries::getUserId, userId));
        if (seriesList.isEmpty()) {
            return List.of();
        }
        List<String> seriesIds = seriesList.stream().map(MediaSeries::getId).toList();
        List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(
                new LambdaQueryWrapper<MediaEpisode>()
                        .in(MediaEpisode::getSeriesId, seriesIds)
                        .eq(MediaEpisode::getWatched, false));
        if (withProgressOnly) {
            episodes = episodes.stream().filter(e -> itemSupport.progressOf(e) > 0).toList();
        }
        if (episodes.isEmpty()) {
            return List.of();
        }
        Map<String, MediaSeries> seriesById = seriesList.stream()
                .collect(Collectors.toMap(MediaSeries::getId, Function.identity()));
        Map<String, MediaSeason> seasonMap = mediaSeasonMapper.selectBatchIds(
                        episodes.stream().map(MediaEpisode::getSeasonId).distinct().toList())
                .stream().collect(Collectors.toMap(MediaSeason::getId, Function.identity()));
        Map<String, MediaEpisodeFile> fileMap = itemSupport.representativeFiles(episodes);
        Map<String, String> fileNameMap = itemSupport.loadFileNameMap(
                fileMap.values().stream().map(MediaEpisodeFile::getFileNodeId).toList());
        return episodes.stream()
                .map(e -> toItem(seriesById.get(e.getSeriesId()), e, fileMap.get(e.getId()),
                        seasonMap.get(e.getSeasonId()), fileNameMap))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 其他候选行：other 行本身即文件级（可选仅带进度）。
     */
    private List<MediaHomeItemSupport.HomeItem> otherItems(String userId, boolean withProgressOnly) {
        List<MediaOther> others = mediaOtherMapper.selectList(
                new LambdaQueryWrapper<MediaOther>()
                        .eq(MediaOther::getUserId, userId)
                        .eq(MediaOther::getWatched, false));
        if (withProgressOnly) {
            others = others.stream().filter(o -> itemSupport.progressOf(o) > 0).toList();
        }
        if (others.isEmpty()) {
            return List.of();
        }
        Map<String, String> fileNameMap = itemSupport.loadFileNameMap(
                others.stream().map(MediaOther::getFileNodeId).toList());
        return others.stream().map(o -> new MediaHomeItemSupport.HomeItem(
                        o.getId(), o.getFileNodeId(), MediaItemType.OTHER.getCode(),
                        o.getName(), fileNameMap.get(o.getFileNodeId()), null,
                        null, null, null, null, null,
                        o.getDurationMs(), o.getProgressMs(), o.getWatched(), o.getLastPlayTime(), null, null))
                .toList();
    }

    /**
     * 集行组装首页条目：无剧或代表文件明细缺失时返回 null。
     */
    public MediaHomeItemSupport.HomeItem toItem(MediaSeries series, MediaEpisode episode,
                                                MediaEpisodeFile file, MediaSeason season,
                                                Map<String, String> fileNameMap) {
        if (series == null || file == null) {
            return null;
        }
        return new MediaHomeItemSupport.HomeItem(episode.getId(), file.getFileNodeId(),
                MediaItemType.EPISODE.getCode(), series.getSeriesName(), fileNameMap.get(file.getFileNodeId()),
                episode.getMetadataId(), series.getId(), series.getSeriesName(), series.getMetadataId(),
                season == null ? null : season.getSeasonNo(), episode.getEpisodeNo(),
                file.getDurationMs(), episode.getProgressMs(), episode.getWatched(), episode.getLastPlayTime(),
                null, null);
    }
}
