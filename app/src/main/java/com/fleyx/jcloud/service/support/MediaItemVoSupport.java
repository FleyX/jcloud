package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 媒体条目视图 URL 组装支撑组件，供条目查询、影视首页聚合、媒体库目录管理等实现类共享。
 * <p>
 * 除纯 URL 拼接外（issue #21 起旧表条目视图组装已删除），还收口文件节点版本参数批量加载
 * （{@link #loadNodeVersionMap}）与元数据海报/背景图 URL 组装（{@link #posterUrlOf}/{@link #backdropUrlOf}），
 * 各查询支撑类与 Home/目录实现类共用同一份实现（工单 08 消除逐字重复）。
 */
@Component
@RequiredArgsConstructor
public class MediaItemVoSupport {

    private final FileMapper fileMapper;

    /**
     * 指定元数据 ID 的海报图 URL（调用方需保证海报存在）。
     * version 非空时拼 {@code ?v=} 版本参数，用于图片覆盖写后使浏览器缓存自然失效；为空时不带。
     */
    public String metadataPosterUrl(String metadataId, Long version) {
        if (metadataId == null) {
            return null;
        }
        String url = "/jcloud/api/media/metadata/" + metadataId + "/poster";
        return version == null ? url : url + "?v=" + version;
    }

    /**
     * 指定元数据 ID 的背景图 URL（调用方需保证背景图存在）。
     * version 非空时拼 {@code ?v=} 版本参数，用于图片覆盖写后使浏览器缓存自然失效；为空时不带。
     */
    public String metadataBackdropUrl(String metadataId, Long version) {
        if (metadataId == null) {
            return null;
        }
        String url = "/jcloud/api/media/metadata/" + metadataId + "/backdrop";
        return version == null ? url : url + "?v=" + version;
    }

    /**
     * 文件预览缩略图 URL（ffmpeg 截图，用于无元数据的条目封面）。
     */
    public String filePreviewPosterUrl(String fileNodeId) {
        return fileNodeId == null ? null : "/jcloud/api/files/" + fileNodeId + "/preview?type=poster";
    }

    /**
     * 文件节点版本映射（nodeId → lastModified），图片覆盖写后版本变化使浏览器缓存失效；
     * FileNode 查不到或 lastModified 为空（脏数据/假 id）的节点不入映射，对应 URL 不带 {@code ?v=}。
     */
    public Map<String, Long> loadNodeVersionMap(List<String> fileNodeIds) {
        List<String> ids = fileNodeIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return fileMapper.selectBatchIds(ids).stream()
                .filter(node -> node.getLastModified() != null)
                .collect(Collectors.toMap(FileNode::getId, FileNode::getLastModified));
    }

    /**
     * 元数据海报图 URL，无海报时返回 null；海报文件节点存在时附带版本参数。
     */
    public String posterUrlOf(MediaMetadata metadata, Map<String, Long> nodeVersionMap) {
        if (metadata == null || metadata.getPosterFileNodeId() == null) {
            return null;
        }
        return metadataPosterUrl(metadata.getId(), nodeVersionMap.get(metadata.getPosterFileNodeId()));
    }

    /**
     * 元数据背景图 URL，无背景图时返回 null；背景图文件节点存在时附带版本参数。
     */
    public String backdropUrlOf(MediaMetadata metadata, Map<String, Long> nodeVersionMap) {
        if (metadata == null || metadata.getBackdropFileNodeId() == null) {
            return null;
        }
        return metadataBackdropUrl(metadata.getId(), nodeVersionMap.get(metadata.getBackdropFileNodeId()));
    }
}
