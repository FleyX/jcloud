package com.fleyx.jcloud.common.event;

import org.springframework.context.ApplicationEvent;

/**
 * 文件同步完成事件（ADR 0020「同步后扫描」）。
 * <p>
 * 用户存储空间同步与远程挂载同步在任务完成（COMPLETED 或 PARTIAL）后发布，
 * 由媒体模块监听并触发该用户所有媒体库的扫描。完全失败（FAILED）不发布。
 */
public class SyncCompletedEvent extends ApplicationEvent {

    /**
     * 同步来源：用户存储空间同步。
     */
    public static final String TYPE_USER = "user";

    /**
     * 同步来源：远程挂载同步。
     */
    public static final String TYPE_REMOTE_MOUNT = "remote_mount";

    private final String userId;
    private final String syncType;

    /**
     * 创建事件。
     *
     * @param source   事件源
     * @param userId   用户 ID
     * @param syncType 同步来源（{@link #TYPE_USER} / {@link #TYPE_REMOTE_MOUNT}）
     */
    public SyncCompletedEvent(Object source, String userId, String syncType) {
        super(source);
        this.userId = userId;
        this.syncType = syncType;
    }

    /**
     * 获取用户 ID。
     *
     * @return 用户 ID
     */
    public String getUserId() {
        return userId;
    }

    /**
     * 获取同步来源。
     *
     * @return 同步来源
     */
    public String getSyncType() {
        return syncType;
    }
}
