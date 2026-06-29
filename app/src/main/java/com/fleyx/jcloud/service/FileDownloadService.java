package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.bo.BatchDownloadResult;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.bo.FileZipTask;
import com.fleyx.jcloud.model.dto.FileBatchDownloadDto;

/**
 * 批量下载服务。
 */
public interface FileDownloadService {

    /**
     * 批量下载：根据阈值决定同步流式返回 ZIP，或创建后台预生成任务。
     *
     * @param dto    批量下载请求
     * @param userId 用户 ID
     * @return 下载结果（流式或任务）
     */
    BatchDownloadResult downloadBatch(FileBatchDownloadDto dto, String userId);

    /**
     * 查询任务状态。
     *
     * @param taskId 任务 ID
     * @param userId 用户 ID
     * @return 任务对象
     */
    FileZipTask getTask(String taskId, String userId);

    /**
     * 下载已完成的预生成 ZIP。
     *
     * @param taskId 任务 ID
     * @param userId 用户 ID
     * @return 文件下载结果
     */
    FileDownloadResult downloadTaskResult(String taskId, String userId);
}
