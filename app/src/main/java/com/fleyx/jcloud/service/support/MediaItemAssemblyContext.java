package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.model.po.MediaMetadata;

import java.util.Map;
import java.util.Set;

/**
 * 海报墙装配上下文（工单 08）：驱动管线批量加载的元数据/海报版本/文件名映射、代表数据与当前用户收藏态，
 * 逐行卡片装配（toCard）只依赖该上下文，不感知数据来源。
 *
 * @param <F> 代表数据值类型（电影=明细行、剧集=集列表、其他=文件节点 ID）
 */
public class MediaItemAssemblyContext<F> {

    private final Map<String, MediaMetadata> metadataMap;
    private final Map<String, Long> nodeVersionMap;
    private final Map<String, String> fileNameMap;
    private final Map<String, F> representativeFiles;
    private final Set<String> favoritedIds;

    public MediaItemAssemblyContext(Map<String, MediaMetadata> metadataMap, Map<String, Long> nodeVersionMap,
                                    Map<String, String> fileNameMap, Map<String, F> representativeFiles,
                                    Set<String> favoritedIds) {
        this.metadataMap = metadataMap;
        this.nodeVersionMap = nodeVersionMap;
        this.fileNameMap = fileNameMap;
        this.representativeFiles = representativeFiles;
        this.favoritedIds = favoritedIds;
    }

    /**
     * 行元数据（metadataId 为空或未命中返回 null）。
     */
    public MediaMetadata metadataOf(String metadataId) {
        return metadataId == null ? null : metadataMap.get(metadataId);
    }

    /**
     * 海报文件节点版本映射（nodeId → lastModified），供海报 URL 版本参数拼接。
     */
    public Map<String, Long> nodeVersionMap() {
        return nodeVersionMap;
    }

    /**
     * 文件节点名（fileNodeId 为空或未命中返回 null）。
     */
    public String fileNameOf(String fileNodeId) {
        return fileNodeId == null ? null : fileNameMap.get(fileNodeId);
    }

    /**
     * 行代表数据（以行 ID 为键；未命中返回 null）。
     */
    public F representativeOf(String rowId) {
        return representativeFiles.get(rowId);
    }

    /**
     * 当前用户是否已收藏该行。
     */
    public boolean isFavorited(String id) {
        return favoritedIds.contains(id);
    }
}
