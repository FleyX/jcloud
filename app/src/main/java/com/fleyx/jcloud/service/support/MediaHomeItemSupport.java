package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.po.MediaSeason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 影视首页查询共享原语（票据 06 从 MediaHomeQuerySupport 拆分）。
 * <p>
 * 首页四分区（继续观看/接下来/最新电影/最新剧集）共用的进度判定、代表文件选取、
 * 文件名加载与排序原语，统一收敛于此，各分区组件各自持有一份实例。
 */
@Component
@RequiredArgsConstructor
public class MediaHomeItemSupport {

    /**
     * 看完阈值：进度达到时长的 95% 视为看完。
     */
    static final double FINISHED_RATIO = 0.95;

    private final MediaEpisodeFileMapper mediaEpisodeFileMapper;
    private final MediaMovieFileMapper mediaMovieFileMapper;
    private final FileMapper fileMapper;

    /**
     * 首页聚合条目（标题级行 + 代表文件事实）。
     *
     * @param posterFallbackFileNodeId 海报回退文件节点（最新剧集卡无代表文件时用于文件预览海报），
     *                                 不复制到 {@code MediaItemVo.fileNodeId}，不参与排序与时间计算
     */
    public record HomeItem(String id, String fileNodeId, String itemType, String title, String fileName,
                           String metadataId, String seriesId, String seriesName, String seriesMetadataId,
                           Integer seasonNo, Integer episodeNo, Long durationMs, Long progressMs,
                           LocalDateTime lastPlayTime, LocalDateTime addedTime, String posterFallbackFileNodeId) {
    }

    /**
     * 各集代表文件明细：last_play_file_id 优先，否则取最早一条。
     */
    public Map<String, MediaEpisodeFile> representativeFiles(List<MediaEpisode> episodes) {
        List<String> episodeIds = episodes.stream().map(MediaEpisode::getId).toList();
        Map<String, List<MediaEpisodeFile>> filesByEpisode = mediaEpisodeFileMapper.selectList(
                        new LambdaQueryWrapper<MediaEpisodeFile>().in(MediaEpisodeFile::getEpisodeId, episodeIds))
                .stream().collect(Collectors.groupingBy(MediaEpisodeFile::getEpisodeId));
        Map<String, MediaEpisodeFile> result = new HashMap<>();
        for (MediaEpisode episode : episodes) {
            MediaEpisodeFile chosen = MediaItemVoSupport.pickRepresentative(
                    filesByEpisode.getOrDefault(episode.getId(), List.of()), episode.getLastPlayFileId(),
                    MediaEpisodeFile::getId, MediaEpisodeFile::getCreateTime);
            if (chosen != null) {
                result.put(episode.getId(), chosen);
            }
        }
        return result;
    }

    /**
     * 各电影代表文件明细：last_play_file_id 优先，否则取最早一条。
     */
    public Map<String, MediaMovieFile> representativeMovieFiles(List<MediaMovie> movies) {
        List<String> movieIds = movies.stream().map(MediaMovie::getId).toList();
        Map<String, List<MediaMovieFile>> filesByMovie = mediaMovieFileMapper.selectList(
                        new LambdaQueryWrapper<MediaMovieFile>().in(MediaMovieFile::getMovieId, movieIds))
                .stream().collect(Collectors.groupingBy(MediaMovieFile::getMovieId));
        Map<String, MediaMovieFile> result = new HashMap<>();
        for (MediaMovie movie : movies) {
            MediaMovieFile chosen = MediaItemVoSupport.pickRepresentative(
                    filesByMovie.getOrDefault(movie.getId(), List.of()), movie.getLastPlayFileId(),
                    MediaMovieFile::getId, MediaMovieFile::getCreateTime);
            if (chosen != null) {
                result.put(movie.getId(), chosen);
            }
        }
        return result;
    }

    public long progressOf(MediaMovie movie) {
        return movie.getProgressMs() == null ? 0L : movie.getProgressMs();
    }

    public long progressOf(MediaEpisode episode) {
        return episode.getProgressMs() == null ? 0L : episode.getProgressMs();
    }

    public long progressOf(MediaOther other) {
        return other.getProgressMs() == null ? 0L : other.getProgressMs();
    }

    public boolean isFinished(HomeItem item) {
        return item.durationMs() != null && item.durationMs() > 0
                && item.progressMs() != null && item.progressMs() >= FINISHED_RATIO * item.durationMs();
    }

    public boolean isFinished(MediaEpisodeFile file, MediaEpisode episode) {
        Long duration = file == null ? null : file.getDurationMs();
        return duration != null && duration > 0 && progressOf(episode) >= FINISHED_RATIO * duration;
    }

    public Comparator<HomeItem> lastPlayDesc() {
        return Comparator.comparing(HomeItem::lastPlayTime,
                Comparator.nullsLast(Comparator.reverseOrder()));
    }

    /**
     * 各集按季号/集号排序（季号缺失排后），同集按实体 ID 兜底。
     */
    public Comparator<MediaEpisode> seasonEpisodeOrder(Map<String, MediaSeason> seasonMap) {
        return Comparator.comparing(
                        (MediaEpisode e) -> seasonMap.get(e.getSeasonId()) == null
                                ? null : seasonMap.get(e.getSeasonId()).getSeasonNo(),
                        Comparator.nullsLast(Integer::compareTo))
                .thenComparing(MediaEpisode::getEpisodeNo, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(MediaEpisode::getId);
    }

    public Map<String, String> loadFileNameMap(List<String> fileNodeIds) {
        List<String> ids = fileNodeIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return fileMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(FileNode::getId, FileNode::getName));
    }
}
