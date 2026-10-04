package com.fleyx.jcloud.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.enums.NotificationEventType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.TransferTaskStatus;
import com.fleyx.jcloud.common.event.NotificationEvent;
import com.fleyx.jcloud.common.event.TransferSubmittedEvent;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.TransferTaskMapper;
import com.fleyx.jcloud.model.bo.TransferItem;
import com.fleyx.jcloud.model.po.TransferTask;
import com.fleyx.jcloud.service.support.SyncTaskSupport;
import com.fleyx.jcloud.service.support.TransferContext;
import com.fleyx.jcloud.service.support.TransferNodeSupport;
import com.fleyx.jcloud.util.UserReadWriteLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.context.ApplicationEventPublisher;
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
    private final UserReadWriteLock userReadWriteLock;
    private final ApplicationEventPublisher eventPublisher;

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
                RLock lock = userReadWriteLock.writeLock(task.getUserId());
                lock.lock();
                try {
                    transferNodeSupport.transferTopLevel(item, task, ctx);
                } catch (Exception e) {
                    log.error("传输项执行失败，taskId={}，nodeId={}", taskId, item.getNodeId(), e);
                    ctx.recordFailure(item.getName(), "传输失败: " + e.getMessage());
                } finally {
                    lock.unlock();
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
        publishResultNotification(task, update.getStatus(), ctx.getSuccessCount(), ctx.getFailCount(),
                failureSummary(ctx, update.getStatus()));
    }

    /**
     * 任务失败时取首个失败项作为错误摘要；其余状态不附带错误信息。
     *
     * @param ctx    执行上下文
     * @param status 终结状态
     * @return 错误摘要，无则为 null
     */
    private String failureSummary(TransferContext ctx, String status) {
        if (!TransferTaskStatus.FAILED.getValue().equals(status)) {
            return null;
        }
        TransferContext.FailItem first = ctx.firstFailure();
        return first == null ? null : first.name() + "：" + first.reason();
    }

    private void failTask(TransferTask task, String errorMsg) {
        TransferTask update = new TransferTask();
        update.setId(task.getId());
        update.setStatus(TransferTaskStatus.FAILED.getValue());
        update.setErrorMsg(syncTaskSupport.truncate(errorMsg));
        update.setEndTime(LocalDateTime.now());
        update.setUpdateTime(LocalDateTime.now());
        transferTaskMapper.updateById(update);
        publishResultNotification(task, TransferTaskStatus.FAILED.getValue(),
                valueOrZero(task.getSuccessCount()), valueOrZero(task.getFailCount()), update.getErrorMsg());
    }

    /**
     * 任务终结时向发起者发布站内通知；用户主动取消（CANCELED）不发。
     * <p>
     * 执行路径为异步无事务，直接发布进程内事件，由通知模块异步落库。
     *
     * @param task         传输任务
     * @param status       终结状态
     * @param successCount 成功数
     * @param failCount    失败数
     * @param errorMsg     错误摘要，可空
     */
    private void publishResultNotification(TransferTask task, String status,
                                           long successCount, long failCount, String errorMsg) {
        if (TransferTaskStatus.CANCELED.getValue().equals(status)) {
            return;
        }
        String opLabel = "move".equals(task.getOpType()) ? "移动" : "复制";
        boolean failed = TransferTaskStatus.FAILED.getValue().equals(status);
        boolean partial = TransferTaskStatus.PARTIAL.getValue().equals(status);
        NotificationEventType eventType = failed
                ? NotificationEventType.TRANSFER_FAILED : NotificationEventType.TRANSFER_COMPLETED;
        String title;
        if (failed) {
            title = "跨来源传输失败";
        } else if (partial) {
            title = "跨来源传输部分成功";
        } else {
            title = "跨来源传输完成";
        }
        String content = failed && errorMsg != null
                ? String.format("%s任务失败：%s", opLabel, errorMsg)
                : String.format("%s任务%s：成功 %d 个，失败 %d 个", opLabel, partial ? "部分成功" : "完成",
                        successCount, failCount);
        eventPublisher.publishEvent(new NotificationEvent(this, eventType, task.getUserId(), title, content));
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
