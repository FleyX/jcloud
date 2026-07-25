package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 跨来源传输任务视图对象。
 */
@Data
public class TransferTaskVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务 ID。
     */
    private String id;

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
     * 任务状态。
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
     * 失败明细 JSON。
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

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;
}
