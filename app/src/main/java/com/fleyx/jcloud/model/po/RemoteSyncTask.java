package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 远程挂载同步任务实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_remote_sync_task")
public class RemoteSyncTask extends SoftDeleteEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 远程挂载 ID。
     */
    private String remoteMountId;

    /**
     * 触发方式：manual / scheduled。
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
     * 扫描到的远程节点总数。
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
