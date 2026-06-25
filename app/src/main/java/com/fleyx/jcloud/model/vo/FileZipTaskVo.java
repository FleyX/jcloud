package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * ZIP 批量下载任务视图。
 */
@Data
public class FileZipTaskVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务 ID。
     */
    private String taskId;

    /**
     * 任务状态：pending / running / completed / failed。
     */
    private String status;

    /**
     * 总字节数。
     */
    private Long totalBytes;

    /**
     * 失败原因。
     */
    private String message;
}
