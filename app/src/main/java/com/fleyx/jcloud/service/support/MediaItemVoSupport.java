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
}
