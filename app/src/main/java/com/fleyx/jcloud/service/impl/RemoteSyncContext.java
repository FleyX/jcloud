package com.fleyx.jcloud.service.impl;

import java.util.ArrayList;
import java.util.List;

/**
 * 远程同步任务执行上下文，用于统计同步结果与收集错误。
 */
public class RemoteSyncContext {

    private final String taskId;
    private final String userId;
    private final String mountId;
    private long totalCount;
    private long successCount;
    private long failCount;
    private final List<String> errors = new ArrayList<>();

    public RemoteSyncContext(String taskId, String userId, String mountId) {
        this.taskId = taskId;
        this.userId = userId;
        this.mountId = mountId;
    }

    public void incrementTotal() {
        totalCount++;
    }

    public void incrementSuccess() {
        successCount++;
    }

    public void incrementFail() {
        failCount++;
    }

    public void addError(String message) {
        if (message == null) {
            return;
        }
        errors.add(message);
    }

    public String getErrorMessage() {
        if (errors.isEmpty()) {
            return null;
        }
        String joined = String.join("; ", errors);
        return joined.length() > 4000 ? joined.substring(0, 4000) : joined;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getUserId() {
        return userId;
    }

    public String getMountId() {
        return mountId;
    }

    public long getTotalCount() {
        return totalCount;
    }

    public long getSuccessCount() {
        return successCount;
    }

    public long getFailCount() {
        return failCount;
    }
}
