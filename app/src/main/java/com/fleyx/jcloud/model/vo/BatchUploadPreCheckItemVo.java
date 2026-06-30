package com.fleyx.jcloud.model.vo;

import lombok.Data;

/**
 * 批量上传前冲突预检响应项。
 */
@Data
public class BatchUploadPreCheckItemVo {

    /**
     * 前端请求时传入的文件唯一标识。
     */
    private String clientFileId;

    /**
     * 处理状态：success 或 error。
     */
    private String status;

    /**
     * 错误码，状态为 error 时返回。
     */
    private String errorCode;

    /**
     * 错误描述，状态为 error 时返回。
     */
    private String errorMessage;

    /**
     * 预检结果数据，状态为 success 时返回。
     */
    private UploadPreCheckVo data;
}
