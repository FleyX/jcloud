package com.fleyx.jcloud.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 用户存储空间迁移任务已提交事件。
 */
@Getter
public class UserMigrationSubmittedEvent extends ApplicationEvent {

    private final String taskId;

    public UserMigrationSubmittedEvent(Object source, String taskId) {
        super(source);
        this.taskId = taskId;
    }
}
