package com.fleyx.jcloud.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.TransferTaskStatus;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.common.event.TransferSubmittedEvent;
import com.fleyx.jcloud.mapper.TransferTaskMapper;
import com.fleyx.jcloud.model.bo.TransferItem;
import com.fleyx.jcloud.model.po.TransferTask;
import com.fleyx.jcloud.service.support.SyncTaskSupport;
import com.fleyx.jcloud.service.support.TransferContext;
import com.fleyx.jcloud.service.support.TransferNodeSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 跨来源传输任务执行器。
 * <p>
 * 事务提交后异步执行，逐文件中转传输，支持取消与失败明细记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferTaskExecutor {

    private final TransferTaskMapper transferTaskMapper;
    private final TransferNodeSupport transferNodeSupport;
    private final SyncTaskSupport syncTaskSupport;
    private final ObjectMapper objectMapper;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(TransferSubmittedEvent event) {
        execute(event.getTaskId());
    }

    /**
     * 执行传输任务（仅 PENDING 状态可执行）。
     *
     * @param taskId 任务 ID
     */
    public void execute(String taskId) {
        TransferTask task = transferTaskMapper.selectById(taskId);
        if (task == null || !TransferTaskStatus.PENDING.getValue().equals(task.getStatus())) {
            return;
        }
        markRunning(task);
        TransferContext ctx = new TransferContext(task, transferTaskMapper, objectMapper);
        try {
            List<TransferItem> items;
            try {
                items = objectMapper.readValue(task.getItems(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, TransferItem.class));
            } catch (Exception e) {
                throw new SystemException(ResultCode.SYSTEM_ERROR, "解析传输项失败", e);
            }
            for (TransferItem item : items) {
                if (ctx.checkCancelled()) {
                    break;
                }
                try {
                    transferNodeSupport.transferTopLevel(item, task, ctx);
                } catch (Exception e) {
                    log.error("传输项执行失败，taskId={}，nodeId={}", taskId, item.getNodeId(), e);
                    ctx.recordFailure(item.getName(), "传输失败: " + e.getMessage());
                }
            }
            finish(task, ctx);
        } catch (Exception e) {
            log.error("跨来源传输任务失败，taskId={}", taskId, e);
            failTask(task, e.getMessage());
        }
    }

    private void markRunning(TransferTask task) {
        TransferTask update = new TransferTask();
        update.setId(task.getId());
        update.setStatus(TransferTaskStatus.RUNNING.getValue());
        update.setStartTime(LocalDateTime.now());
        update.setUpdateTime(LocalDateTime.now());
        transferTaskMapper.updateById(update);
    }

    private void finish(TransferTask task, TransferContext ctx) {
        TransferTask update = new TransferTask();
        update.setId(task.getId());
        if (ctx.isCancelled()) {
            update.setStatus(TransferTaskStatus.CANCELED.getValue());
        } else if (ctx.getFailCount() > 0 && ctx.getSuccessCount() == 0) {
            update.setStatus(TransferTaskStatus.FAILED.getValue());
        } else if (ctx.getFailCount() > 0) {
            update.setStatus(TransferTaskStatus.PARTIAL.getValue());
        } else {
            update.setStatus(TransferTaskStatus.COMPLETED.getValue());
        }
        update.setSuccessCount(ctx.getSuccessCount());
        update.setFailCount(ctx.getFailCount());
        update.setFailDetail(ctx.failDetailJson());
        update.setEndTime(LocalDateTime.now());
        update.setUpdateTime(LocalDateTime.now());
        transferTaskMapper.updateById(update);
    }

    private void failTask(TransferTask task, String errorMsg) {
        TransferTask update = new TransferTask();
        update.setId(task.getId());
        update.setStatus(TransferTaskStatus.FAILED.getValue());
        update.setErrorMsg(syncTaskSupport.truncate(errorMsg));
        update.setEndTime(LocalDateTime.now());
        update.setUpdateTime(LocalDateTime.now());
        transferTaskMapper.updateById(update);
    }
}
