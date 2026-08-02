package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 媒体条目视图组装支撑组件，供条目查询、影视首页聚合等实现类共享。
 */
@Component
@RequiredArgsConstructor
public class MediaItemVoSupport {

    private final MediaMetadataMapper mediaMetadataMapper;
    private final FileMapper fileMapper;

    /**
     * 条目列表转视图（填充文件名、元数据标题/海报/评分等）。
     *
     * @param items        条目列表
     * @param withMetadata 是否加载元数据（海报墙/卡片需要，其他类型条目可为 false）
     * @return 条目视图列表
     */
    public List<MediaItemVo> toItemVos(List<MediaItem> items, boolean withMetadata) {
        Map<String, MediaMetadata> metadataMap = withMetadata ? loadMetadataMap(items) : Map.of();
        Map<String, String> fileNameMap = loadFileNameMap(items);
        List<MediaItemVo> result = new ArrayList<>();
        for (MediaItem item : items) {
            MediaItemVo vo = new MediaItemVo();
            vo.setId(item.getId());
            vo.setFileNodeId(item.getFileNodeId());
            vo.setItemType(item.getItemType());
            vo.setFileName(fileNameMap.get(item.getFileNodeId()));
            vo.setMatchStatus(item.getMatchStatus());
            vo.setMetadataId(item.getMetadataId());
            vo.setSeriesId(item.getSeriesId());
            vo.setSeriesName(item.getSeriesName());
            vo.setSeasonNo(item.getSeasonNo());
            vo.setEpisodeNo(item.getEpisodeNo());
            vo.setDurationMs(item.getDurationMs());
            vo.setProgressMs(item.getProgressMs());
            vo.setLastPlayTime(item.getLastPlayTime());
            MediaMetadata metadata = item.getMetadataId() == null ? null : metadataMap.get(item.getMetadataId());
            if (metadata != null) {
                vo.setTitle(metadata.getTitle());
                vo.setReleaseDate(metadata.getReleaseDate());
                vo.setVoteAverage(metadata.getVoteAverage());
                vo.setPosterUrl(posterUrlOf(metadata));
            }
            if (vo.getTitle() == null) {
                vo.setTitle(vo.getFileName());
            }
            result.add(vo);
        }
        return result;
    }

    /**
     * 元数据海报图 URL，无海报时返回 null。
     */
    public String posterUrlOf(MediaMetadata metadata) {
        return metadata == null || metadata.getPosterFileNodeId() == null ? null
                : metadataPosterUrl(metadata.getId());
    }

    /**
     * 元数据背景图 URL，无背景图时返回 null。
     */
    public String backdropUrlOf(MediaMetadata metadata) {
        return metadata == null || metadata.getBackdropFileNodeId() == null ? null
                : metadataBackdropUrl(metadata.getId());
    }

    /**
     * 指定元数据 ID 的海报图 URL（调用方需保证海报存在）。
     */
    public String metadataPosterUrl(String metadataId) {
        return metadataId == null ? null : "/jcloud/api/media/metadata/" + metadataId + "/poster";
    }

    /**
     * 指定元数据 ID 的背景图 URL（调用方需保证背景图存在）。
     */
    public String metadataBackdropUrl(String metadataId) {
        return metadataId == null ? null : "/jcloud/api/media/metadata/" + metadataId + "/backdrop";
    }

    /**
     * 文件预览缩略图 URL（ffmpeg 截图，用于无元数据的条目封面）。
     */
    public String filePreviewPosterUrl(String fileNodeId) {
        return fileNodeId == null ? null : "/jcloud/api/files/" + fileNodeId + "/preview?type=poster";
    }

    private Map<String, MediaMetadata> loadMetadataMap(List<MediaItem> items) {
        List<String> metadataIds = items.stream().map(MediaItem::getMetadataId)
                .filter(Objects::nonNull).distinct().toList();
        if (metadataIds.isEmpty()) {
            return Map.of();
        }
        return mediaMetadataMapper.selectBatchIds(metadataIds).stream()
                .collect(Collectors.toMap(MediaMetadata::getId, Function.identity()));
    }

    private Map<String, String> loadFileNameMap(List<MediaItem> items) {
        List<String> fileNodeIds = items.stream().map(MediaItem::getFileNodeId)
                .filter(Objects::nonNull).distinct().toList();
        if (fileNodeIds.isEmpty()) {
            return Map.of();
        }
        return fileMapper.selectBatchIds(fileNodeIds).stream()
                .collect(Collectors.toMap(FileNode::getId, FileNode::getName));
    }
}
