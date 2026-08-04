package com.fleyx.jcloud.service.support;

import org.springframework.stereotype.Component;

/**
 * 媒体条目视图 URL 组装支撑组件，供条目查询、影视首页聚合、媒体库目录管理等实现类共享。
 * <p>
 * issue #21 起仅保留纯 URL 拼接方法：旧表条目视图组装（toItemVos 等）已随旧表弃用删除。
 */
@Component
public class MediaItemVoSupport {

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
}
