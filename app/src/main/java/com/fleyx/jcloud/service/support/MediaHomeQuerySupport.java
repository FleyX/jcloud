package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSeasonV2Mapper;
import com.fleyx.jcloud.mapper.MediaSeriesV2Mapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.po.MediaSeasonV2;
import com.fleyx.jcloud.model.po.MediaSeriesV2;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 影视首页聚合查询支撑组件（issue #19 起切新表）。
 * <p>
 * 继续观看：电影/集/其他三类标题级行中「有进度且未看完」的条目（时长为空无法计算阈值时视为未看完），
 * 按最近播放时间倒序；接下来：每部有观看记录的剧取第一集未观看集（在播集归入继续观看，一律排除），
 * 按剧最近播放时间倒序。时长等文件事实取自代表文件明细（last_play_file_id 优先，缺省取最早）。
 */
@Component
@RequiredArgsConstructor
public class MediaHomeQuerySupport {

    /**
     * 看完阈值：进度达到时长的 95% 视为看完。
     */
    static final double FINISHED_RATIO = 0.95;

    private final MediaMovieMapper mediaMovieMapper;
    private final MediaMovieFileMapper mediaMovieFileMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaEpisodeFileMapper mediaEpisodeFileMapper;
    private final MediaOtherMapper mediaOtherMapper;
    private final MediaSeriesV2Mapper mediaSeriesV2Mapper;
    private final MediaSeasonV2Mapper mediaSeasonV2Mapper;
    private final FileMapper fileMapper;

    /**
     * 首页聚合条目（标题级行 + 代表文件事实）。
     */
    public record HomeItem(String id, String fileNodeId, String itemType, String title, String fileName,
                           String metadataId, String seriesId, String seriesName, String seriesMetadataId,
                           Integer seasonNo, Integer episodeNo, Long durationMs, Long progressMs,
                           LocalDateTime lastPlayTime) {
    }

    /**
     * 继续观看：三类标题级行中「有进度且未看完」的条目，按最近播放时间倒序，截取 limit 条。
     */
    public List<HomeItem> listContinueWatching(String userId, int limit) {
        List<HomeItem> items = new ArrayList<>();
        items.addAll(movieItems(userId, true));
        items.addAll(episodeItems(userId, true));
        items.addAll(otherItems(userId, true));
        return items.stream().filter(i -> !isFinished(i))
                .sorted(lastPlayDesc()).limit(limit).toList();
    }

    /**
     * 接下来：每部有观看记录的剧取第一集未观看集，按剧最近播放时间倒序，截取 limit 条。
     */
    public List<HomeItem> listNextUp(String userId, int limit) {
        List<MediaSeriesV2> seriesList = mediaSeriesV2Mapper.selectList(
                new LambdaQueryWrapper<MediaSeriesV2>().eq(MediaSeriesV2::getUserId, userId));
        if (seriesList.isEmpty()) {
            return List.of();
        }
        List<String> seriesIds = seriesList.stream().map(MediaSeriesV2::getId).toList();
        List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(
                new LambdaQueryWrapper<MediaEpisode>().in(MediaEpisode::getSeriesId, seriesIds));
        if (episodes.isEmpty()) {
            return List.of();
        }
        Map<String, MediaEpisodeFile> fileMap = representativeFiles(episodes);
        Map<String, MediaSeasonV2> seasonMap = mediaSeasonV2Mapper.selectBatchIds(
                        episodes.stream().map(MediaEpisode::getSeasonId).distinct().toList())
                .stream().collect(Collectors.toMap(MediaSeasonV2::getId, Function.identity()));
        Map<String, MediaSeriesV2> seriesById = seriesList.stream()
                .collect(Collectors.toMap(MediaSeriesV2::getId, Function.identity()));
        Map<String, String> fileNameMap = loadFileNameMap(
                fileMap.values().stream().map(MediaEpisodeFile::getFileNodeId).toList());
        Map<String, List<MediaEpisode>> bySeries = episodes.stream()
                .collect(Collectors.groupingBy(MediaEpisode::getSeriesId));
        List<SeriesNextUp> candidates = new ArrayList<>();
        for (Map.Entry<String, List<MediaEpisode>> entry : bySeries.entrySet()) {
            MediaSeriesV2 series = seriesById.get(entry.getKey());
            if (series == null) {
                continue;
            }
            List<MediaEpisode> seriesEpisodes = entry.getValue();
            boolean hasHistory = seriesEpisodes.stream()
                    .anyMatch(e -> progressOf(e) > 0 || e.getLastPlayTime() != null);
            if (!hasHistory) {
                continue;
            }
            seriesEpisodes.sort(seasonEpisodeOrder(seasonMap));
            seriesEpisodes.stream()
                    .filter(e -> progressOf(e) == 0 && !isFinished(fileMap.get(e.getId()), e))
                    .findFirst()
                    .ifPresent(pick -> candidates.add(new SeriesNextUp(
                            toItem(series, pick, fileMap.get(pick.getId()), seasonMap.get(pick.getSeasonId()),
                                    fileNameMap),
                            maxLastPlayTime(seriesEpisodes))));
        }
        candidates.sort(Comparator.comparing(SeriesNextUp::lastPlayTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return candidates.stream().limit(limit).map(SeriesNextUp::item).toList();
    }

    /**
     * 电影候选行：全部电影行（可选仅带进度），时长取代表文件明细。
     */
    private List<HomeItem> movieItems(String userId, boolean withProgressOnly) {
        List<MediaMovie> movies = mediaMovieMapper.selectList(
                new LambdaQueryWrapper<MediaMovie>().eq(MediaMovie::getUserId, userId));
        if (withProgressOnly) {
            movies = movies.stream().filter(m -> progressOf(m) > 0).toList();
        }
        if (movies.isEmpty()) {
            return List.of();
        }
        Map<String, MediaMovieFile> fileMap = representativeMovieFiles(movies);
        Map<String, String> fileNameMap = loadFileNameMap(
                fileMap.values().stream().map(MediaMovieFile::getFileNodeId).toList());
        List<HomeItem> result = new ArrayList<>();
        for (MediaMovie movie : movies) {
            MediaMovieFile file = fileMap.get(movie.getId());
            if (file == null) {
                continue;
            }
            result.add(new HomeItem(movie.getId(), file.getFileNodeId(), MediaItemType.MOVIE.getCode(),
                    movie.getTitle(), fileNameMap.get(file.getFileNodeId()), movie.getMetadataId(),
                    null, null, null, null, null,
                    file.getDurationMs(), movie.getProgressMs(), movie.getLastPlayTime()));
        }
        return result;
    }

    /**
     * 集候选行：全部集行（可选仅带进度），时长取代表文件明细，剧名/季号/剧元数据补齐。
     */
    private List<HomeItem> episodeItems(String userId, boolean withProgressOnly) {
        List<MediaSeriesV2> seriesList = mediaSeriesV2Mapper.selectList(
                new LambdaQueryWrapper<MediaSeriesV2>().eq(MediaSeriesV2::getUserId, userId));
        if (seriesList.isEmpty()) {
            return List.of();
        }
        List<String> seriesIds = seriesList.stream().map(MediaSeriesV2::getId).toList();
        List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(
                new LambdaQueryWrapper<MediaEpisode>().in(MediaEpisode::getSeriesId, seriesIds));
        if (withProgressOnly) {
            episodes = episodes.stream().filter(e -> progressOf(e) > 0).toList();
        }
        if (episodes.isEmpty()) {
            return List.of();
        }
        Map<String, MediaSeriesV2> seriesById = seriesList.stream()
                .collect(Collectors.toMap(MediaSeriesV2::getId, Function.identity()));
        Map<String, MediaSeasonV2> seasonMap = mediaSeasonV2Mapper.selectBatchIds(
                        episodes.stream().map(MediaEpisode::getSeasonId).distinct().toList())
                .stream().collect(Collectors.toMap(MediaSeasonV2::getId, Function.identity()));
        Map<String, MediaEpisodeFile> fileMap = representativeFiles(episodes);
        Map<String, String> fileNameMap = loadFileNameMap(
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
    private List<HomeItem> otherItems(String userId, boolean withProgressOnly) {
        List<MediaOther> others = mediaOtherMapper.selectList(
                new LambdaQueryWrapper<MediaOther>().eq(MediaOther::getUserId, userId));
        if (withProgressOnly) {
            others = others.stream().filter(o -> progressOf(o) > 0).toList();
        }
        if (others.isEmpty()) {
            return List.of();
        }
        Map<String, String> fileNameMap = loadFileNameMap(
                others.stream().map(MediaOther::getFileNodeId).toList());
        return others.stream().map(o -> new HomeItem(o.getId(), o.getFileNodeId(), MediaItemType.OTHER.getCode(),
                        o.getName(), fileNameMap.get(o.getFileNodeId()), null,
                        null, null, null, null, null,
                        o.getDurationMs(), o.getProgressMs(), o.getLastPlayTime()))
                .toList();
    }

    private HomeItem toItem(MediaSeriesV2 series, MediaEpisode episode, MediaEpisodeFile file,
                            MediaSeasonV2 season, Map<String, String> fileNameMap) {
        if (series == null || file == null) {
            return null;
        }
        return new HomeItem(episode.getId(), file.getFileNodeId(), MediaItemType.EPISODE.getCode(),
                series.getSeriesName(), fileNameMap.get(file.getFileNodeId()), episode.getMetadataId(),
                series.getId(), series.getSeriesName(), series.getMetadataId(),
                season == null ? null : season.getSeasonNo(), episode.getEpisodeNo(),
                file.getDurationMs(), episode.getProgressMs(), episode.getLastPlayTime());
    }

    private record SeriesNextUp(HomeItem item, LocalDateTime lastPlayTime) {
    }

    /**
     * 各集代表文件明细：last_play_file_id 优先，否则取最早一条。
     */
    private Map<String, MediaEpisodeFile> representativeFiles(List<MediaEpisode> episodes) {
        List<String> episodeIds = episodes.stream().map(MediaEpisode::getId).toList();
        Map<String, List<MediaEpisodeFile>> filesByEpisode = mediaEpisodeFileMapper.selectList(
                        new LambdaQueryWrapper<MediaEpisodeFile>().in(MediaEpisodeFile::getEpisodeId, episodeIds))
                .stream().collect(Collectors.groupingBy(MediaEpisodeFile::getEpisodeId));
        Map<String, MediaEpisodeFile> result = new HashMap<>();
        for (MediaEpisode episode : episodes) {
            List<MediaEpisodeFile> files = filesByEpisode.getOrDefault(episode.getId(), List.of());
            if (files.isEmpty()) {
                continue;
            }
            result.put(episode.getId(), files.stream()
                    .filter(f -> f.getId().equals(episode.getLastPlayFileId())).findFirst()
                    .orElseGet(() -> files.stream()
                            .min(Comparator.comparing(MediaEpisodeFile::getCreateTime,
                                            Comparator.nullsLast(Comparator.naturalOrder()))
                                    .thenComparing(MediaEpisodeFile::getId))
                            .orElse(files.getFirst())));
        }
        return result;
    }

    /**
     * 各电影代表文件明细：last_play_file_id 优先，否则取最早一条。
     */
    private Map<String, MediaMovieFile> representativeMovieFiles(List<MediaMovie> movies) {
        List<String> movieIds = movies.stream().map(MediaMovie::getId).toList();
        Map<String, List<MediaMovieFile>> filesByMovie = mediaMovieFileMapper.selectList(
                        new LambdaQueryWrapper<MediaMovieFile>().in(MediaMovieFile::getMovieId, movieIds))
                .stream().collect(Collectors.groupingBy(MediaMovieFile::getMovieId));
        Map<String, MediaMovieFile> result = new HashMap<>();
        for (MediaMovie movie : movies) {
            List<MediaMovieFile> files = filesByMovie.getOrDefault(movie.getId(), List.of());
            if (files.isEmpty()) {
                continue;
            }
            result.put(movie.getId(), files.stream()
                    .filter(f -> f.getId().equals(movie.getLastPlayFileId())).findFirst()
                    .orElseGet(() -> files.stream()
                            .min(Comparator.comparing(MediaMovieFile::getCreateTime,
                                            Comparator.nullsLast(Comparator.naturalOrder()))
                                    .thenComparing(MediaMovieFile::getId))
                            .orElse(files.getFirst())));
        }
        return result;
    }

    private Comparator<MediaEpisode> seasonEpisodeOrder(Map<String, MediaSeasonV2> seasonMap) {
        return Comparator.comparing(
                        (MediaEpisode e) -> seasonMap.get(e.getSeasonId()) == null
                                ? null : seasonMap.get(e.getSeasonId()).getSeasonNo(),
                        Comparator.nullsLast(Integer::compareTo))
                .thenComparing(MediaEpisode::getEpisodeNo, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(MediaEpisode::getId);
    }

    private LocalDateTime maxLastPlayTime(List<MediaEpisode> episodes) {
        return episodes.stream().map(MediaEpisode::getLastPlayTime)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    private long progressOf(MediaMovie movie) {
        return movie.getProgressMs() == null ? 0L : movie.getProgressMs();
    }

    private long progressOf(MediaEpisode episode) {
        return episode.getProgressMs() == null ? 0L : episode.getProgressMs();
    }

    private long progressOf(MediaOther other) {
        return other.getProgressMs() == null ? 0L : other.getProgressMs();
    }

    private boolean isFinished(HomeItem item) {
        return item.durationMs() != null && item.durationMs() > 0
                && item.progressMs() != null && item.progressMs() >= FINISHED_RATIO * item.durationMs();
    }

    private boolean isFinished(MediaEpisodeFile file, MediaEpisode episode) {
        Long duration = file == null ? null : file.getDurationMs();
        return duration != null && duration > 0 && progressOf(episode) >= FINISHED_RATIO * duration;
    }

    private Comparator<HomeItem> lastPlayDesc() {
        return Comparator.comparing(HomeItem::lastPlayTime,
                Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private Map<String, String> loadFileNameMap(List<String> fileNodeIds) {
        List<String> ids = fileNodeIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return fileMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(FileNode::getId, FileNode::getName));
    }
}
