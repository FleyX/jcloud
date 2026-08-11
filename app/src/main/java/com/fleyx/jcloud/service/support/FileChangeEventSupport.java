package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.event.FileTreeChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 文件树变更事件发布辅助组件（ADR 0025）。
 * <p>
 * 事务内调用时把发布推迟到事务提交后（after-commit），事务回滚不发布；
 * WebDAV、传输等无事务路径直接发布。先例：{@code MediaDirectoryServiceImpl.submitScanAfterCommit}。
 */
@Component
@RequiredArgsConstructor
public class FileChangeEventSupport {

    private final ApplicationEventPublisher publisher;

    /**
     * 事务提交后发布文件树变更事件；无活跃事务时直接发布。
     *
     * @param event 文件树变更事件
     */
    public void publishAfterCommit(FileTreeChangedEvent event) {
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
