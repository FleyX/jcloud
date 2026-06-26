package com.fleyx.jcloud.model.dto;

import lombok.Data;

/**
 * 分片上传完成请求参数。
 */
@Data
public class ChunkedUploadCompleteDto {

    /**
     * 冲突解决策略：skip / overwrite / keep。
     * <p>
     * 当目标位置已存在同名节点时生效；若未指定且存在冲突则报错。
     */
    private String strategy;
}
