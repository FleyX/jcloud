package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 操作结果视图。
 */
@Data
public class OperationResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 源节点 ID。
     */
    private String sourceId;

    /**
     * 源节点名称。
     */
    private String sourceName;

    /**
     * 执行状态：success / skipped / failed。
     */
    private String status;

    /**
     * 处理后的名称（自动重命名时有效）。
     */
    private String newName;

    /**
     * 失败原因（失败时有效）。
     */
    private String message;

    /**
     * 结果节点 ID（成功时有效）。
     */
    private String nodeId;
}
