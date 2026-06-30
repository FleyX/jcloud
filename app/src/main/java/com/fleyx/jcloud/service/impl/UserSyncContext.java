package com.fleyx.jcloud.service.impl;

/**
 * 用户存储空间同步执行上下文，记录执行过程中的统计与错误信息。
 */
class UserSyncContext {

    private final String taskId;
    private final String userId;
    private final String spaceId;
    private long totalCount;
    private long successCount;
    private long failCount;
    private final StringBuilder errorBuilder;

    UserSyncContext(String taskId, String userId, String spaceId) {
        this.taskId = taskId;
        this.userId = userId;
        this.spaceId = spaceId;
        this.errorBuilder = new StringBuilder();
    }

    String getTaskId() {
        return taskId;
    }

    String getUserId() {
        return userId;
    }

    String getSpaceId() {
        return spaceId;
    }

    long getTotalCount() {
        return totalCount;
    }

    long getSuccessCount() {
        return successCount;
    }

    long getFailCount() {
        return failCount;
    }

    String getErrorMessage() {
        return errorBuilder.toString();
    }

    void incrementSuccess() {
        totalCount++;
        successCount++;
    }

    void addError(String message) {
        totalCount++;
        failCount++;
        if (!errorBuilder.isEmpty()) {
            errorBuilder.append("; ");
        }
        errorBuilder.append(message);
    }
}
