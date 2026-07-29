package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 电视剧/季层级维护支撑组件，供扫描、目录管理、条目匹配等实现类共享。
 */
@Component
@RequiredArgsConstructor
public class MediaSeriesSupport {

    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaItemMapper mediaItemMapper;

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
        MediaSeries update = new MediaSeries();
        update.setId(series.getId());
        update.setMetadataId(metadataId);
        update.setMatchStatus(metadataId == null ? MediaMatchStatus.UNMATCHED.getCode() : MediaMatchStatus.MATCHED.getCode());
        mediaSeriesMapper.updateById(update);
        series.setMetadataId(metadataId);
        series.setMatchStatus(update.getMatchStatus());
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
