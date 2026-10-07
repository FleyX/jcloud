package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.event.NotificationEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 通知事件发布支撑类（ADR 0039）。
 * <p>
 * 事务内触发通知时把发布推迟到事务提交后（after-commit），事务回滚则不发布，
 * 避免向用户/管理员发出实际未生效的操作通知。无活跃事务时直接发布。
 * 模式参照 {@link FileChangeEventSupport#publishAfterCommit}。
 */
@Component
@RequiredArgsConstructor
public class NotificationEventSupport {

    private final ApplicationEventPublisher publisher;

    /**
     * 事务提交后发布通知事件；无活跃事务时直接发布。
     *
     * @param event 通知事件
     */
    public void publishAfterCommit(NotificationEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.publishEvent(event);
                }
            });
        } else {
            publisher.publishEvent(event);
        }
    }
}
