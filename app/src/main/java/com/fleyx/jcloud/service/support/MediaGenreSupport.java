package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.MediaGenreVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 类型聚合支撑组件（issue #22 类型页）：电影/剧集库行元数据 genres（逗号分隔）拆分聚合，
 * 返回类型名 + 条目数 + 代表海报；季/集不参与聚合，其他库无类型概念直接返回空。
 */
@Component
@RequiredArgsConstructor
public class MediaGenreSupport {

    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final MediaMovieMapper mediaMovieMapper;
    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final FileMapper fileMapper;
    private final MediaItemVoSupport mediaItemVoSupport;

    /**
     * 聚合指定媒体库的类型列表：按条目数降序、同数按名称升序。
     *
     * @param userId      用户 ID
     * @param directoryId 媒体库 ID
     * @return 类型列表，其他库返回空列表
     */
    public List<MediaGenreVo> listGenres(String userId, String directoryId) {
        MediaDirectory directory = requireOwned(directoryId, userId);
        if (MediaType.OTHER.getCode().equals(directory.getMediaType())) {
            return List.of();
        }
        List<String> metadataIds = MediaType.MOVIE.getCode().equals(directory.getMediaType())
                ? mediaMovieMapper.selectList(new LambdaQueryWrapper<MediaMovie>()
                        .eq(MediaMovie::getDirectoryId, directoryId))
                .stream().map(MediaMovie::getMetadataId).toList()
                : mediaSeriesMapper.selectList(new LambdaQueryWrapper<MediaSeries>()
                        .eq(MediaSeries::getDirectoryId, directoryId))
                .stream().map(MediaSeries::getMetadataId).toList();

        Map<String, MediaMetadata> metadataMap = loadMetadataMap(metadataIds);
        Map<String, GenreAggregate> aggregates = new HashMap<>();
        for (MediaMetadata metadata : metadataMap.values()) {
            if (metadata.getGenres() == null || metadata.getGenres().isBlank()) {
                continue;
            }
            for (String raw : metadata.getGenres().split(",")) {
                String name = raw.trim();
                if (name.isEmpty()) {
                    continue;
                }
                GenreAggregate aggregate = aggregates.computeIfAbsent(name, k -> new GenreAggregate());
                aggregate.count++;
                if (aggregate.posterMetadataId == null && metadata.getPosterFileNodeId() != null) {
                    aggregate.posterMetadataId = metadata.getId();
                }
            }
        }
        Map<String, Long> nodeVersionMap = nodeVersionMap(aggregates, metadataMap);
        return aggregates.entrySet().stream()
                .map(e -> toVo(e.getKey(), e.getValue(), metadataMap, nodeVersionMap))
                .sorted(Comparator.comparing(MediaGenreVo::getItemCount, Comparator.reverseOrder())
                        .thenComparing(MediaGenreVo::getName))
                .toList();
    }

    /**
     * 类型代表海报的版本映射（posterMetadataId → lastModified）：聚合过程已加载全部元数据，
     * 直接从该映射取各类型代表海报的 posterFileNodeId 走文件节点版本映射。
     */
    private Map<String, Long> nodeVersionMap(Map<String, GenreAggregate> aggregates,
                                             Map<String, MediaMetadata> metadataMap) {
        List<String> posterNodeIds = aggregates.values().stream()
                .map(a -> a.posterMetadataId)
                .filter(Objects::nonNull)
                .map(metadataMap::get)
                .filter(Objects::nonNull)
                .map(MediaMetadata::getPosterFileNodeId)
                .toList();
        List<String> ids = posterNodeIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return fileMapper.selectBatchIds(ids).stream()
                .filter(node -> node.getLastModified() != null)
                .collect(Collectors.toMap(FileNode::getId, FileNode::getLastModified));
    }

    /**
     * 校验媒体库归属：不存在或不属于该用户时抛业务异常。
     */
    private MediaDirectory requireOwned(String id, String userId) {
        MediaDirectory directory = mediaDirectoryMapper.selectById(id);
        if (directory == null || !userId.equals(directory.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体库不存在");
        }
        return directory;
    }

    private Map<String, MediaMetadata> loadMetadataMap(List<String> metadataIds) {
        List<String> ids = metadataIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return mediaMetadataMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(MediaMetadata::getId, Function.identity()));
    }

    private MediaGenreVo toVo(String name, GenreAggregate aggregate, Map<String, MediaMetadata> metadataMap,
                              Map<String, Long> nodeVersionMap) {
        MediaGenreVo vo = new MediaGenreVo();
        vo.setName(name);
        vo.setItemCount(aggregate.count);
        MediaMetadata posterMetadata = aggregate.posterMetadataId == null ? null
                : metadataMap.get(aggregate.posterMetadataId);
        vo.setPosterUrl(posterMetadata == null ? null
                : mediaItemVoSupport.metadataPosterUrl(posterMetadata.getId(),
                        nodeVersionMap.get(posterMetadata.getPosterFileNodeId())));
        return vo;
    }

    /**
     * 单类型聚合态：条目计数与首张有海报条目的元数据 ID。
     */
    private static final class GenreAggregate {
        private long count;
        private String posterMetadataId;
    }
}
