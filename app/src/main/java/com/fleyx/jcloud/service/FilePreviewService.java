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

    /**
     * 以文件所有者身份生成或返回已缓存的文件预览。
     * 适用于公开分享等不依赖当前登录用户的场景。
     *
     * @param fileNodeId    文件节点 ID
     * @param ownerUserId   文件所有者用户 ID
     * @param ownerUserCode 文件所有者用户编码（用户名）
     * @param type          预览类型
     * @return 预览结果
     */
    PreviewResult previewByOwner(String fileNodeId, String ownerUserId, String ownerUserCode, PreviewType type);
}
