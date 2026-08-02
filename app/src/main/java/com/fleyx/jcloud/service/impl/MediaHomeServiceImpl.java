package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.mapper.MediaMetadataV2Mapper;
import com.fleyx.jcloud.model.po.MediaMetadataV2;
import com.fleyx.jcloud.model.vo.MediaHomeVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.service.MediaDirectoryService;
import com.fleyx.jcloud.service.MediaHomeService;
import com.fleyx.jcloud.service.support.MediaHomeQuerySupport;
import com.fleyx.jcloud.service.support.MediaItemVoSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 影视首页聚合服务实现（issue #19 起切新表，委托 {@link MediaHomeQuerySupport}）。
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
    private final MediaHomeQuerySupport mediaHomeQuerySupport;
    private final MediaMetadataV2Mapper mediaMetadataV2Mapper;
    private final MediaItemVoSupport mediaItemVoSupport;

    @Override
    public MediaHomeVo getHome(String userId) {
        MediaHomeVo vo = new MediaHomeVo();
        vo.setLibraries(mediaDirectoryService.list(userId));
        vo.setContinueWatching(toItemVos(mediaHomeQuerySupport.listContinueWatching(userId, SECTION_LIMIT)));
        vo.setNextUp(toItemVos(mediaHomeQuerySupport.listNextUp(userId, SECTION_LIMIT)));
        return vo;
    }

    /**
     * 首页条目转视图：补齐文件名与元数据（标题/海报/评分），标题缺失回退条目名。
     */
    private List<MediaItemVo> toItemVos(List<MediaHomeQuerySupport.HomeItem> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        List<String> metadataIds = items.stream().map(MediaHomeQuerySupport.HomeItem::metadataId)
                .filter(Objects::nonNull).distinct().toList();
        Map<String, MediaMetadataV2> metadataMap = metadataIds.isEmpty() ? Map.of() : loadMetadataMap(metadataIds);
        Map<String, MediaMetadataV2> seriesMetadataMap = loadSeriesMetadataMap(items);
        List<MediaItemVo> result = new ArrayList<>();
        for (MediaHomeQuerySupport.HomeItem item : items) {
            MediaItemVo vo = new MediaItemVo();
            vo.setId(item.id());
            vo.setFileNodeId(item.fileNodeId());
            vo.setItemType(item.itemType());
            vo.setFileName(item.fileName());
            vo.setSeriesId(item.seriesId());
            vo.setSeriesName(item.seriesName());
            vo.setSeasonNo(item.seasonNo());
            vo.setEpisodeNo(item.episodeNo());
            vo.setDurationMs(item.durationMs());
            vo.setProgressMs(item.progressMs());
            vo.setLastPlayTime(item.lastPlayTime());
            MediaMetadataV2 metadata = item.metadataId() == null ? null : metadataMap.get(item.metadataId());
            MediaMetadataV2 seriesMetadata = item.seriesMetadataId() == null
                    ? null : seriesMetadataMap.get(item.seriesMetadataId());
            MediaMetadataV2 posterMetadata = metadata != null && metadata.getPosterFileNodeId() != null
                    ? metadata : seriesMetadata;
            if (metadata != null) {
                vo.setTitle(metadata.getTitle());
                vo.setReleaseDate(metadata.getReleaseDate());
                vo.setVoteAverage(metadata.getVoteAverage());
            }
            if (posterMetadata != null) {
                vo.setPosterUrl(mediaItemVoSupport.metadataPosterUrl(posterMetadata.getId()));
            }
            if (vo.getTitle() == null) {
                vo.setTitle(item.title());
            }
            result.add(vo);
        }
        return result;
    }

    private Map<String, MediaMetadataV2> loadMetadataMap(List<String> metadataIds) {
        return mediaMetadataV2Mapper.selectBatchIds(metadataIds).stream()
                .collect(Collectors.toMap(MediaMetadataV2::getId, Function.identity()));
    }

    /**
     * 集卡片用剧级元数据兜底海报（集元数据未派生时海报墙仍可用）。
     */
    private Map<String, MediaMetadataV2> loadSeriesMetadataMap(List<MediaHomeQuerySupport.HomeItem> items) {
        List<String> seriesMetadataIds = items.stream()
                .map(MediaHomeQuerySupport.HomeItem::seriesMetadataId)
                .filter(Objects::nonNull).distinct().toList();
        if (seriesMetadataIds.isEmpty()) {
            return Map.of();
        }
        return mediaMetadataV2Mapper.selectBatchIds(seriesMetadataIds).stream()
                .collect(Collectors.toMap(MediaMetadataV2::getId, Function.identity()));
    }
}
