package com.fleyx.jcloud.model.bo;

import com.fleyx.jcloud.common.enums.FileZipTaskStatus;

import java.nio.file.Path;
import java.time.Instant;

/**
 * ZIP 批量下载任务业务对象。
 */
public record FileZipTask(String taskId,
                          Long userId,
                          FileZipTaskStatus status,
                          Path zipPath,
                          long totalBytes,
                          String message,
                          Instant createdAt) {
}
