package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.vo.MediaHomeVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.service.MediaDirectoryService;
import com.fleyx.jcloud.service.MediaHomeService;
import com.fleyx.jcloud.service.support.MediaItemVoSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 影视首页聚合服务实现。
 * <p>
 * 继续观看：有播放进度且未看完的条目（时长为空无法计算阈值时视为未看完），按最近播放时间倒序；
 * 接下来：仅电视剧，取每部有观看记录的剧按季/集顺序的第一集未观看集
 * （在播集属于继续观看，一律排除），按剧最近播放时间倒序。
 */
@Service
@RequiredArgsConstructor
public class MediaHomeServiceImpl implements MediaHomeService {

    /**
     * 首页各分段的最大条数。
     */
    static final int SECTION_LIMIT = 16;

    /**
     * 看完阈值：进度达到时长的 95% 视为看完。
     */
    static final double FINISHED_RATIO = 0.95;

    private final MediaDirectoryService mediaDirectoryService;
    private final MediaItemMapper mediaItemMapper;
    private final MediaItemVoSupport mediaItemVoSupport;

    @Override
    public MediaHomeVo getHome(String userId) {
        MediaHomeVo vo = new MediaHomeVo();
        vo.setLibraries(mediaDirectoryService.list(userId));
        List<MediaItem> inProgress = mediaItemMapper.selectInProgressItems(userId, SECTION_LIMIT);
        vo.setContinueWatching(mediaItemVoSupport.toItemVos(inProgress, true));
        vo.setNextUp(mediaItemVoSupport.toItemVos(pickNextUpItems(userId), true));
        return vo;
    }

    /**
     * 计算「接下来」条目：每部有观看记录的剧取第一集未观看集，按剧最近播放时间倒序。
     */
    private List<MediaItem> pickNextUpItems(String userId) {
        List<MediaItem> episodes = mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                .eq(MediaItem::getUserId, userId)
                .eq(MediaItem::getItemType, MediaItemType.EPISODE.getCode())
                .isNotNull(MediaItem::getSeriesId)
                .select(MediaItem::getId, MediaItem::getSeriesId, MediaItem::getSeasonNo,
                        MediaItem::getEpisodeNo, MediaItem::getProgressMs, MediaItem::getDurationMs,
                        MediaItem::getLastPlayTime));
        Map<String, List<MediaItem>> episodesBySeries = episodes.stream()
                .collect(Collectors.groupingBy(MediaItem::getSeriesId));
        List<SeriesNextUp> candidates = new ArrayList<>();
        for (List<MediaItem> seriesEpisodes : episodesBySeries.values()) {
            boolean hasHistory = seriesEpisodes.stream()
                    .anyMatch(e -> progressOf(e) > 0 || e.getLastPlayTime() != null);
            if (!hasHistory) {
                continue;
            }
            seriesEpisodes.sort(seasonEpisodeOrder());
            seriesEpisodes.stream().filter(e -> progressOf(e) == 0 && !isFinished(e)).findFirst()
                    .ifPresent(pick -> candidates.add(new SeriesNextUp(pick, maxLastPlayTime(seriesEpisodes))));
        }
        candidates.sort(Comparator.comparing(SeriesNextUp::lastPlayTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<String> pickedIds = candidates.stream().limit(SECTION_LIMIT).map(c -> c.item().getId()).toList();
        if (pickedIds.isEmpty()) {
            return List.of();
        }
        // 回表取完整条目字段（上面只为计算选取了部分字段），并保持候选顺序
        Map<String, MediaItem> fullItems = mediaItemMapper.selectBatchIds(pickedIds).stream()
                .collect(Collectors.toMap(MediaItem::getId, i -> i));
        return pickedIds.stream().map(fullItems::get).filter(Objects::nonNull).toList();
    }

    /**
     * 一部剧的「接下来」候选：条目 + 该剧最近播放时间。
     */
    private record SeriesNextUp(MediaItem item, LocalDateTime lastPlayTime) {
    }

    private Comparator<MediaItem> seasonEpisodeOrder() {
        return Comparator.comparing(MediaItem::getSeasonNo, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(MediaItem::getEpisodeNo, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(MediaItem::getId);
    }

    private LocalDateTime maxLastPlayTime(List<MediaItem> episodes) {
        return episodes.stream().map(MediaItem::getLastPlayTime)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    private long progressOf(MediaItem item) {
        return item.getProgressMs() == null ? 0L : item.getProgressMs();
    }

    /**
     * 是否已看完：进度达到时长的 95%；时长为空或为 0 时无法计算阈值，视为未看完。
     */
    private boolean isFinished(MediaItem item) {
        return item.getDurationMs() != null && item.getDurationMs() > 0
                && progressOf(item) >= FINISHED_RATIO * item.getDurationMs();
    }
}
