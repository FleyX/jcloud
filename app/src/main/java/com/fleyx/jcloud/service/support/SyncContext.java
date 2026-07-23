package com.fleyx.jcloud.service.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步任务执行上下文。
 * <p>
 * 统计同步结果与收集错误。计数语义统一为：total = success + fail，
 * 即每个处理条目要么计入成功要么计入失败；仅读取类失败（如目录列举失败）只记错误不计数。
 */
public class SyncContext {

    private final String taskId;
    private final String userId;
    private final String targetId;
    private long totalCount;
    private long successCount;
    private long failCount;
    private final List<String> errors = new ArrayList<>();

    public SyncContext(String taskId, String userId, String targetId) {
        this.taskId = taskId;
        this.userId = userId;
        this.targetId = targetId;
    }

    /**
     * 记录一个条目处理成功（total+1，success+1）。
     */
    public void recordSuccess() {
        totalCount++;
        successCount++;
    }

    /**
     * 记录一个条目处理失败（total+1，fail+1，并收集错误信息）。
     *
     * @param message 错误信息
     */
    public void recordFailure(String message) {
        totalCount++;
        failCount++;
        addError(message);
    }

    /**
     * 仅收集错误信息，不改变计数（用于目录列举等读取类失败）。
     *
     * @param message 错误信息
     */
    public void addError(String message) {
        if (message == null) {
            return;
        }
        errors.add(message);
    }

    /**
     * 汇总的错误信息，无错误返回 {@code null}。
     *
     * @return 错误信息
     */
    public String getErrorMessage() {
        if (errors.isEmpty()) {
            return null;
        }
        return String.join("; ", errors);
    }

    public String getTaskId() {
        return taskId;
    }

    public String getUserId() {
        return userId;
    }

    public String getTargetId() {
        return targetId;
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
