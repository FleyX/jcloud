package com.fleyx.jcloud.common.event;

import org.springframework.context.ApplicationEvent;

/**
 * 远程挂载同步任务已提交事件。
 */
public class RemoteMountSubmittedEvent extends ApplicationEvent {

    private final String taskId;

    public RemoteMountSubmittedEvent(Object source, String taskId) {
        super(source);
        this.taskId = taskId;
    }

    public String getTaskId() {
        return taskId;
    }
}
