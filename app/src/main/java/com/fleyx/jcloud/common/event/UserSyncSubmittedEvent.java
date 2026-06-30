package com.fleyx.jcloud.common.event;

import org.springframework.context.ApplicationEvent;

/**
 * 用户存储空间同步任务已提交事件。
 * <p>
 * 在同步任务持久化后发布，由异步执行器监听并执行实际同步。
 */
public class UserSyncSubmittedEvent extends ApplicationEvent {

    private final String taskId;

    /**
     * 创建事件。
     *
     * @param source 事件源
     * @param taskId 同步任务 ID
     */
    public UserSyncSubmittedEvent(Object source, String taskId) {
        super(source);
        this.taskId = taskId;
    }

    /**
     * 获取任务 ID。
     *
     * @return 任务 ID
     */
    public String getTaskId() {
        return taskId;
    }
}
