package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fleyx.jcloud.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 跨来源传输任务实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "t_transfer_task", autoResultMap = true)
public class TransferTask extends SoftDeleteEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 发起用户 ID。
     */
    private String userId;

    /**
     * 操作类型：copy / move。
     */
    private String opType;

    /**
     * 来源类型：local / remote。
     */
    private String sourceType;

    /**
     * 目标类型：local / remote。
     */
    private String targetType;

    /**
     * 源为远程时的挂载 ID。
     */
    private String sourceMountId;

    /**
     * 目标为远程时的挂载 ID。
     */
    private String targetMountId;

    /**
     * 目标父节点 ID，根目录为虚拟根节点占位 ID。
     */
    private String targetParentId;

    /**
     * 任务状态：PENDING / RUNNING / CANCELLING / CANCELED / COMPLETED / FAILED / PARTIAL。
     */
    private String status;

    /**
     * 待传输文件节点总数。
     */
    private Long totalCount;

    /**
     * 成功传输文件数。
     */
    private Long successCount;

    /**
     * 失败/跳过文件数。
     */
    private Long failCount;

    /**
     * 待传输总字节数。
     */
    private Long totalBytes;

    /**
     * 传输项快照 JSON。
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String items;

    /**
     * 失败明细 JSON（截断）。
     */
    private String failDetail;

    /**
     * 任务级错误信息。
     */
    private String errorMsg;

    /**
     * 任务开始时间。
     */
    private LocalDateTime startTime;

    /**
     * 任务结束时间。
     */
    private LocalDateTime endTime;
}
