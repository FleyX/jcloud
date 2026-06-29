package com.fleyx.jcloud.model.vo;

import lombok.Data;

/**
 * 用户存储空间迁移任务视图。
 */
@Data
public class UserMigrationTaskVo {

    /**
     * 任务 ID。
     */
    private String id;

    /**
     * 用户 ID。
     */
    private String userId;

    /**
     * 源存储空间 ID。
     */
    private String sourceSpaceId;

    /**
     * 目标存储空间 ID。
     */
    private String targetSpaceId;

    /**
     * 新配额（字节）。
     */
    private Long newQuota;

    /**
     * 任务状态。
     */
    private String status;

    /**
     * 总字节数。
     */
    private Long totalBytes;

    /**
     * 已迁移字节数。
     */
    private Long migratedBytes;

    /**
     * 错误信息。
     */
    private String errorMsg;

    /**
     * 创建时间。
     */
    private String createTime;

    /**
     * 更新时间。
     */
    private String updateTime;
}
