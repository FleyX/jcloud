package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.vo.MediaHomeVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.service.MediaDirectoryService;
import com.fleyx.jcloud.service.MediaHomeService;
import com.fleyx.jcloud.service.support.MediaHomeContinueWatchingSupport;
import com.fleyx.jcloud.service.support.MediaHomeItemSupport;
import com.fleyx.jcloud.service.support.MediaHomeLatestSupport;
import com.fleyx.jcloud.service.support.MediaHomeNextUpSupport;
import com.fleyx.jcloud.service.support.MediaItemVoSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 影视首页聚合服务实现（issue #19 起切新表，委托四个分区查询支撑组件，票据 06 拆分）。
 * <p>
 * 继续观看：电影/集/其他标题级行中「有进度且未看完」的条目，按最近播放时间倒序；
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

    private final MediaDirectoryService mediaDirectoryService;
    private final MediaHomeContinueWatchingSupport mediaHomeContinueWatchingSupport;
    private final MediaHomeNextUpSupport mediaHomeNextUpSupport;
    private final MediaHomeLatestSupport mediaHomeLatestSupport;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final MediaItemVoSupport mediaItemVoSupport;

    @Override
    public MediaHomeVo getHome(String userId) {
        MediaHomeVo vo = new MediaHomeVo();
        vo.setLibraries(mediaDirectoryService.list(userId));
        vo.setLatestMovies(toItemVos(mediaHomeLatestSupport.listLatestMovies(userId, SECTION_LIMIT)));
        vo.setLatestSeries(toItemVos(mediaHomeLatestSupport.listLatestSeries(userId, SECTION_LIMIT)));
        vo.setContinueWatching(toItemVos(mediaHomeContinueWatchingSupport.listContinueWatching(userId, SECTION_LIMIT)));
        vo.setNextUp(toItemVos(mediaHomeNextUpSupport.listNextUp(userId, SECTION_LIMIT)));
        return vo;
    }

    /**
     * 首页条目转视图：补齐文件名与元数据（标题/海报/评分），标题缺失回退条目名。
     */
    private List<MediaItemVo> toItemVos(List<MediaHomeItemSupport.HomeItem> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        List<String> metadataIds = items.stream().map(MediaHomeItemSupport.HomeItem::metadataId)
                .filter(Objects::nonNull).distinct().toList();
        Map<String, MediaMetadata> metadataMap = metadataIds.isEmpty() ? Map.of() : loadMetadataMap(metadataIds);
        Map<String, MediaMetadata> seriesMetadataMap = loadSeriesMetadataMap(items);
        Map<String, Long> nodeVersionMap = mediaItemVoSupport.loadNodeVersionMap(
                Stream.concat(metadataMap.values().stream(), seriesMetadataMap.values().stream())
                        .map(MediaMetadata::getPosterFileNodeId)
                        .toList());
        List<MediaItemVo> result = new ArrayList<>();
        for (MediaHomeItemSupport.HomeItem item : items) {
            MediaItemVo vo = new MediaItemVo();
            vo.setId(item.id());
            vo.setFileNodeId(item.fileNodeId());
            vo.setItemType(item.itemType());
            vo.setFileName(item.fileName());
            vo.setMetadataId(item.metadataId());
            vo.setSeriesId(item.seriesId());
            vo.setSeriesName(item.seriesName());
            vo.setSeasonNo(item.seasonNo());
            vo.setEpisodeNo(item.episodeNo());
            vo.setDurationMs(item.durationMs());
            vo.setProgressMs(item.progressMs());
            vo.setLastPlayTime(item.lastPlayTime());
            vo.setAddedTime(item.addedTime());
            MediaMetadata metadata = item.metadataId() == null ? null : metadataMap.get(item.metadataId());
            MediaMetadata seriesMetadata = item.seriesMetadataId() == null
                    ? null : seriesMetadataMap.get(item.seriesMetadataId());
            if (metadata != null) {
                vo.setTitle(metadata.getTitle());
                vo.setReleaseDate(metadata.getReleaseDate());
                vo.setVoteAverage(metadata.getVoteAverage());
            }
            // 仅当所选元数据实际存在海报文件时才下发元数据海报；否则用文件预览兜底（不伪造代表文件 ID）
            MediaMetadata posterMetadata = null;
            if (metadata != null && metadata.getPosterFileNodeId() != null) {
                posterMetadata = metadata;
            } else if (seriesMetadata != null && seriesMetadata.getPosterFileNodeId() != null) {
                posterMetadata = seriesMetadata;
            }
            if (posterMetadata != null) {
                vo.setPosterUrl(mediaItemVoSupport.metadataPosterUrl(posterMetadata.getId(),
                        nodeVersionMap.get(posterMetadata.getPosterFileNodeId())));
            } else if (item.posterFallbackFileNodeId() != null) {
                vo.setPosterUrl(mediaItemVoSupport.filePreviewPosterUrl(item.posterFallbackFileNodeId()));
            }
            if (vo.getTitle() == null) {
                vo.setTitle(item.title());
            }
            result.add(vo);
        }
        return result;
    }

    private Map<String, MediaMetadata> loadMetadataMap(List<String> metadataIds) {
        return mediaMetadataMapper.selectBatchIds(metadataIds).stream()
                .collect(Collectors.toMap(MediaMetadata::getId, Function.identity()));
    }

    /**
     * 集卡片用剧级元数据兜底海报（集元数据未派生时海报墙仍可用）。
     */
    private Map<String, MediaMetadata> loadSeriesMetadataMap(List<MediaHomeItemSupport.HomeItem> items) {
        List<String> seriesMetadataIds = items.stream()
                .map(MediaHomeItemSupport.HomeItem::seriesMetadataId)
                .filter(Objects::nonNull).distinct().toList();
        if (seriesMetadataIds.isEmpty()) {
            return Map.of();
        }
        return mediaMetadataMapper.selectBatchIds(seriesMetadataIds).stream()
                .collect(Collectors.toMap(MediaMetadata::getId, Function.identity()));
    }
}
