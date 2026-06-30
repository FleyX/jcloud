package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 用户存储空间同步任务实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_sync_task")
public class UserSyncTask extends SoftDeleteEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID。
     */
    private String userId;

    /**
     * 触发方式：manual 手动 / scheduled 定时。
     */
    private String type;

    /**
     * 任务状态：PENDING / RUNNING / COMPLETED / FAILED / PARTIAL。
     */
    private String status;

    /**
     * 任务开始时间。
     */
    private LocalDateTime startTime;

    /**
     * 任务结束时间。
     */
    private LocalDateTime endTime;

    /**
     * 扫描到的物理节点总数。
     */
    private Long totalCount;

    /**
     * 成功同步节点数。
     */
    private Long successCount;

    /**
     * 失败/跳过节点数。
     */
    private Long failCount;

    /**
     * 错误信息。
     */
    private String errorMsg;
}
