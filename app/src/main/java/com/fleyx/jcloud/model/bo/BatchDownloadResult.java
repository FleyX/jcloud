package com.fleyx.jcloud.model.bo;

import com.fleyx.jcloud.common.enums.FileZipTaskStatus;

import java.io.InputStream;

/**
 * 批量下载结果：同步流式或异步任务。
 */
public sealed interface BatchDownloadResult {

    /**
     * 同步流式结果。
     *
     * @param fileName  下载文件名
     * @param inputStream ZIP 输入流
     * @param totalSize  ZIP 总字节数
     */
    record StreamResult(String fileName, InputStream inputStream, long totalSize) implements BatchDownloadResult {
    }

    /**
     * 异步任务结果。
     *
     * @param taskId 任务 ID
     * @param status 任务状态
     */
    record TaskResult(String taskId, FileZipTaskStatus status) implements BatchDownloadResult {
    }
}
