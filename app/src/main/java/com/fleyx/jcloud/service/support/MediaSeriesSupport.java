package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.service.TmdbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 电视剧/季层级维护支撑组件，供扫描、目录管理、削刮、条目匹配等实现类共享。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaSeriesSupport {

    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaItemMapper mediaItemMapper;
    private final TmdbService tmdbService;

    /**
     * 剧匹配成功后应用元数据：拉取本地存在的季/集元数据，更新季与非手动修正集条目的元数据关联。
     * 季/集元数据按用户隔离，通过季/集条目已有的 metadata_id 直接绑定，未绑定时新建并回写（ADR 0020）。
     * 单个季/集拉取失败仅记日志，不影响其他季/集。
     *
     * @param series         剧记录
     * @param seriesMetadata 匹配到的剧元数据
     * @param episodeStatus  集条目更新后的匹配状态（自动削刮传 matched，手动修正传 manual）
     */
    public void applySeriesMetadata(MediaSeries series, MediaMetadata seriesMetadata, String episodeStatus) {
        Long seriesTmdbId = seriesMetadata.getTmdbId();
        String userId = series.getUserId();
        List<MediaSeason> seasons = mediaSeasonMapper.selectList(
                new LambdaQueryWrapper<MediaSeason>().eq(MediaSeason::getSeriesId, series.getId()));
        Map<String, MediaMetadata> seasonMetadataMap = new HashMap<>();
        for (MediaSeason season : seasons) {
            if (season.getSeasonNo() == null) {
                continue;
            }
            try {
                MediaMetadata seasonMetadata = tmdbService.getOrFetchSeason(userId, seriesTmdbId, season);
                if (seasonMetadata == null) {
                    continue;
                }
                seasonMetadataMap.put(season.getId(), seasonMetadata);
                if (!seasonMetadata.getId().equals(season.getMetadataId())) {
                    MediaSeason update = new MediaSeason();
                    update.setId(season.getId());
                    update.setMetadataId(seasonMetadata.getId());
                    mediaSeasonMapper.updateById(update);
                }
            } catch (Exception e) {
                log.warn("季元数据拉取失败: series={}, season={}, error={}",
                        series.getSeriesName(), season.getSeasonNo(), e.getMessage());
            }
        }
        List<MediaItem> episodes = mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                .eq(MediaItem::getSeriesId, series.getId()));
        for (MediaItem episode : episodes) {
            if (MediaMatchStatus.MANUAL.getCode().equals(episode.getMatchStatus())) {
                continue;
            }
            MediaMetadata episodeMetadata = resolveEpisodeMetadata(userId, episode, seasonMetadataMap);
            MediaItem update = new MediaItem();
            update.setId(episode.getId());
            update.setMetadataId(episodeMetadata == null ? seriesMetadata.getId() : episodeMetadata.getId());
            update.setMatchStatus(episodeStatus);
            mediaItemMapper.updateById(update);
        }
    }

    /**
     * 解析集条目的集元数据：所属季元数据缺失或季数据中无该集时返回 null（回退绑定剧元数据）。
     */
    private MediaMetadata resolveEpisodeMetadata(String userId, MediaItem episode,
                                                 Map<String, MediaMetadata> seasonMetadataMap) {
        MediaMetadata seasonMetadata = episode.getSeasonId() == null ? null : seasonMetadataMap.get(episode.getSeasonId());
        if (seasonMetadata == null) {
            return null;
        }
        try {
            return tmdbService.getOrFetchEpisode(userId, episode, seasonMetadata);
        } catch (Exception e) {
            log.warn("集元数据拉取失败: item={}, episode={}, error={}",
                    episode.getId(), episode.getEpisodeNo(), e.getMessage());
            return null;
        }
    }

    /**
     * 获取或创建剧（按用户+剧名唯一）。
     *
     * @param userId     用户 ID
     * @param seriesName 剧名
     * @param cache      本次扫描的剧缓存（seriesName → MediaSeries），可为 null
     * @return 剧记录
     */
    public MediaSeries getOrCreateSeries(String userId, String seriesName, Map<String, MediaSeries> cache) {
        if (cache != null && cache.containsKey(seriesName)) {
            return cache.get(seriesName);
        }
        MediaSeries series = mediaSeriesMapper.selectOne(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getUserId, userId)
                .eq(MediaSeries::getSeriesName, seriesName));
        if (series == null) {
            series = new MediaSeries();
            series.setUserId(userId);
            series.setSeriesName(seriesName);
            series.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
            mediaSeriesMapper.insert(series);
        }
        if (cache != null) {
            cache.put(seriesName, series);
        }
        return series;
    }

    /**
     * 获取或创建季。
     *
     * @param seriesId 剧 ID
     * @param seasonNo 季号，可为 null
     * @param cache    本次扫描的季缓存（seriesId:seasonNo → MediaSeason），可为 null
     * @return 季记录
     */
    public MediaSeason getOrCreateSeason(String seriesId, Integer seasonNo, Map<String, MediaSeason> cache) {
        String key = seriesId + ":" + seasonNo;
        if (cache != null && cache.containsKey(key)) {
            return cache.get(key);
        }
        LambdaQueryWrapper<MediaSeason> wrapper = new LambdaQueryWrapper<MediaSeason>()
                .eq(MediaSeason::getSeriesId, seriesId);
        if (seasonNo == null) {
            wrapper.isNull(MediaSeason::getSeasonNo);
        } else {
            wrapper.eq(MediaSeason::getSeasonNo, seasonNo);
        }
        MediaSeason season = mediaSeasonMapper.selectOne(wrapper);
        if (season == null) {
            season = new MediaSeason();
            season.setSeriesId(seriesId);
            season.setSeasonNo(seasonNo);
            mediaSeasonMapper.insert(season);
        }
        if (cache != null) {
            cache.put(key, season);
        }
        return season;
    }

    /**
     * 剧集元数据匹配成功后同步到剧表（手动修正过的剧不覆盖）。
     *
     * @param series     剧记录
     * @param metadataId 元数据 ID，null 表示未匹配
     */
    public void applySeriesMatch(MediaSeries series, String metadataId) {
        if (MediaMatchStatus.MANUAL.getCode().equals(series.getMatchStatus())) {
            return;
        }
        String matchStatus = metadataId == null ? MediaMatchStatus.UNMATCHED.getCode() : MediaMatchStatus.MATCHED.getCode();
        mediaSeriesMapper.update(null, new LambdaUpdateWrapper<MediaSeries>()
                .eq(MediaSeries::getId, series.getId())
                .set(MediaSeries::getMetadataId, metadataId)
                .set(MediaSeries::getMatchStatus, matchStatus));
        series.setMetadataId(metadataId);
        series.setMatchStatus(matchStatus);
    }

    /**
     * 同步剧的首播年份（剧文件夹改名后由扫描回填），可写入 null。
     *
     * @param series 剧记录
     * @param year   解析出的年份，可为 null
     */
    public void syncReleaseYear(MediaSeries series, Integer year) {
        if (Objects.equals(series.getReleaseYear(), year)) {
            return;
        }
        mediaSeriesMapper.update(null, new LambdaUpdateWrapper<MediaSeries>()
                .eq(MediaSeries::getId, series.getId())
                .set(MediaSeries::getReleaseYear, year));
        series.setReleaseYear(year);
    }

    /**
     * 重置剧的匹配状态（条目变化时由扫描调用）：手动修正的保留，
     * 其余置为未匹配并清空元数据，等待削刮重新匹配。
     *
     * @param series 剧记录
     */
    public void resetMatch(MediaSeries series) {
        if (MediaMatchStatus.MANUAL.getCode().equals(series.getMatchStatus())) {
            return;
        }
        if (MediaMatchStatus.UNMATCHED.getCode().equals(series.getMatchStatus()) && series.getMetadataId() == null) {
            return;
        }
        mediaSeriesMapper.update(null, new LambdaUpdateWrapper<MediaSeries>()
                .eq(MediaSeries::getId, series.getId())
                .set(MediaSeries::getMetadataId, null)
                .set(MediaSeries::getMatchStatus, MediaMatchStatus.UNMATCHED.getCode()));
        series.setMetadataId(null);
        series.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
    }

    /**
     * 重算剧的最早文件修改时间（添加时间排序依据）。
     *
     * @param seriesIds 剧 ID 集合
     */
    public void recalcMinFileLastModified(Set<String> seriesIds) {
        if (seriesIds == null || seriesIds.isEmpty()) {
            return;
        }
        for (String seriesId : seriesIds) {
            Long min = mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                            .eq(MediaItem::getSeriesId, seriesId)
                            .isNotNull(MediaItem::getFileLastModified)
                            .orderByAsc(MediaItem::getFileLastModified)
                            .last("limit 1"))
                    .stream().map(MediaItem::getFileLastModified).filter(Objects::nonNull).findFirst().orElse(null);
            MediaSeries update = new MediaSeries();
            update.setId(seriesId);
            update.setMinFileLastModified(min);
            mediaSeriesMapper.updateById(update);
        }
    }

    /**
     * 清理用户名下没有任何集引用的孤儿剧/季（目录删除、路径变更后调用）。
     *
     * @param userId 用户 ID
     */
    public void cleanupOrphans(String userId) {
        List<MediaSeries> allSeries = mediaSeriesMapper.selectList(
                new LambdaQueryWrapper<MediaSeries>().eq(MediaSeries::getUserId, userId));
        if (allSeries.isEmpty()) {
            return;
        }
        Set<String> referenced = new HashSet<>(mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                        .eq(MediaItem::getUserId, userId)
                        .isNotNull(MediaItem::getSeriesId)
                        .select(MediaItem::getSeriesId))
                .stream().map(MediaItem::getSeriesId).toList());
        List<String> orphanIds = allSeries.stream().map(MediaSeries::getId)
                .filter(id -> !referenced.contains(id)).toList();
        if (!orphanIds.isEmpty()) {
            mediaSeasonMapper.delete(new LambdaQueryWrapper<MediaSeason>().in(MediaSeason::getSeriesId, orphanIds));
            mediaSeriesMapper.deleteBatchIds(orphanIds);
        }
    }
}
