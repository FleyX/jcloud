package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.enums.PreviewType;
import com.fleyx.jcloud.model.bo.PreviewResult;

/**
 * 文件预览业务接口。
 */
public interface FilePreviewService {

    /**
     * 生成或返回已缓存的文件预览。
     *
     * @param fileNodeId 文件节点 ID
     * @param userId     用户 ID
     * @param type       预览类型
     * @return 预览结果
     */
    PreviewResult preview(String fileNodeId, String userId, PreviewType type);
}
