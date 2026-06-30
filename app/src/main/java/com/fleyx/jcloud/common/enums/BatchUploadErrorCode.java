package com.fleyx.jcloud.common.enums;

import lombok.Getter;

/**
 * 批量上传接口 item 级错误码。
 */
@Getter
public enum BatchUploadErrorCode {

    /**
     * 缺少 clientFileId。
     */
    MISSING_CLIENT_FILE_ID("缺少 clientFileId"),

    /**
     * batch 内 clientFileId 重复。
     */
    DUPLICATE_CLIENT_FILE_ID("batch 内 clientFileId 重复"),

    /**
     * batch 内目标路径+文件名重复。
     */
    DUPLICATE_FILE_IN_BATCH("batch 内存在重复文件"),

    /**
     * 文件名非法。
     */
    INVALID_FILE_NAME("文件名非法"),

    /**
     * 文件大小非法。
     */
    INVALID_FILE_SIZE("文件大小非法"),

    /**
     * 目标父目录不存在。
     */
    PARENT_NOT_FOUND("目标父目录不存在"),

    /**
     * 相对路径存在路径穿越风险。
     */
    PATH_TRAVERSAL("相对路径非法"),

    /**
     * 用户存储空间不足。
     */
    INSUFFICIENT_SPACE("用户存储空间不足"),

    /**
     * 分片上传初始化失败。
     */
    INIT_FAILED("分片上传初始化失败"),

    /**
     * 业务处理失败（兜底）。
     */
    BUSINESS_ERROR("业务处理失败"),

    /**
     * 系统内部错误（兜底）。
     */
    SYSTEM_ERROR("系统内部错误");

    private final String message;

    BatchUploadErrorCode(String message) {
        this.message = message;
    }
}
