package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 媒体条目视图 URL 组装支撑组件，供条目查询、影视首页聚合、媒体库目录管理等实现类共享。
 * <p>
 * 除纯 URL 拼接外（issue #21 起旧表条目视图组装已删除），还收口文件节点版本参数批量加载
 * （{@link #loadNodeVersionMap}）与元数据海报/背景图 URL 组装（{@link #posterUrlOf}/{@link #backdropUrlOf}），
 * 并下沉海报墙管线的批量加载原语（{@link #loadMetadataMap}/{@link #loadFileNameMap}）与代表文件选取助手
 * （{@link #pickRepresentative}），各查询支撑类与 Home/目录实现类共用同一份实现
 * （工单 08 消除逐字重复；票据 10 起首页与播放链路复用代表文件选取助手）。
 */
@Component
@RequiredArgsConstructor
public class MediaItemVoSupport {

    private final FileMapper fileMapper;
    private final MediaMetadataMapper mediaMetadataMapper;

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

    /**
     * 元数据批量加载（id → 实体），null/空输入返回空映射；供海报墙装配管线共用。
     */
    public Map<String, MediaMetadata> loadMetadataMap(List<String> metadataIds) {
        List<String> ids = metadataIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return mediaMetadataMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(MediaMetadata::getId, Function.identity()));
    }

    /**
     * 文件节点名批量加载（id → name），null/空输入返回空映射。
     */
    public Map<String, String> loadFileNameMap(List<String> fileNodeIds) {
        List<String> ids = fileNodeIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return fileMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(FileNode::getId, FileNode::getName));
    }

    /**
     * 空白字符串归一化为 null（trim 后），用于分页过滤装配。
     */
    public static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /**
     * 代表文件选取：last_play_file_id 命中的明细优先，否则取 create_time 升序（null 排后）最早一条、
     * id 兜底；空列表返回 null。泛型助手供三型海报墙、影视首页与播放链路共用（票据 10 复用同一实现）。
     *
     * @param files            待选明细行
     * @param lastPlayFileId   最近播放文件明细 ID，可为空
     * @param idGetter         明细行 ID 访问器
     * @param createTimeGetter 明细行入库时间访问器
     */
    public static <T> T pickRepresentative(List<T> files, String lastPlayFileId,
                                           Function<T, String> idGetter, Function<T, LocalDateTime> createTimeGetter) {
        if (files.isEmpty()) {
            return null;
        }
        return files.stream().filter(f -> lastPlayFileId != null && lastPlayFileId.equals(idGetter.apply(f))).findFirst()
                .orElseGet(() -> files.stream()
                        .min(Comparator.comparing(createTimeGetter,
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(idGetter))
                        .orElse(files.getFirst()));
    }
}
