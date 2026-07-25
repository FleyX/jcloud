package com.fleyx.jcloud.common.event;

import org.springframework.context.ApplicationEvent;

/**
 * 跨来源传输任务已提交事件。
 */
public class TransferSubmittedEvent extends ApplicationEvent {

    private final String taskId;

    public TransferSubmittedEvent(Object source, String taskId) {
        super(source);
        this.taskId = taskId;
    }

    public String getTaskId() {
        return taskId;
    }
}
