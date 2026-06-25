package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户存储空间迁移任务实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_migration_task")
public class UserMigrationTask extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID。
     */
    private Long userId;

    /**
     * 源存储空间 ID。
     */
    private Long sourceSpaceId;

    /**
     * 目标存储空间 ID。
     */
    private Long targetSpaceId;

    /**
     * 新配额（字节）。
     */
    private Long newQuota;

    /**
     * 任务状态：PENDING / RUNNING / COMPLETED / FAILED。
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
}
