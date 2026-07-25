package com.fleyx.jcloud.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.TransferTaskStatus;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.TransferTaskMapper;
import com.fleyx.jcloud.model.po.TransferTask;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨来源传输执行上下文：计数、失败明细、取消标记与进度回写。
 */
public class TransferContext {

    /**
     * 失败明细最大长度（与数据库列一致）。
     */
    private static final int MAX_DETAIL_LENGTH = 4000;

    private final TransferTask task;
    private final TransferTaskMapper mapper;
    private final ObjectMapper objectMapper;

    @Getter
    private long successCount;

    @Getter
    private long failCount;

    @Getter
    private boolean cancelled;

    private final List<FailItem> failDetails = new ArrayList<>();

    public TransferContext(TransferTask task, TransferTaskMapper mapper, ObjectMapper objectMapper) {
        this.task = task;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录一个文件传输成功并回写进度。
     */
    public void recordSuccess() {
        successCount++;
        flush();
    }

    /**
     * 记录一个文件传输失败并回写进度。
     *
     * @param name   文件名称
     * @param reason 失败原因
     */
    public void recordFailure(String name, String reason) {
        failCount++;
        failDetails.add(new FailItem(name, reason));
        flush();
    }

    /**
     * 检查任务是否被用户取消（每次检查刷新数据库状态）。
     *
     * @return 是否已取消
     */
    public boolean checkCancelled() {
        if (cancelled) {
            return true;
        }
        TransferTask latest = mapper.selectById(task.getId());
        if (latest != null && TransferTaskStatus.CANCELLING.getValue().equals(latest.getStatus())) {
            cancelled = true;
        }
        return cancelled;
    }

    /**
     * 失败明细 JSON（截断至列长度）。
     *
     * @return 失败明细 JSON，无失败为 {@code null}
     */
    public String failDetailJson() {
        if (failDetails.isEmpty()) {
            return null;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(failDetails);
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "序列化失败明细失败", e);
        }
        return json.length() > MAX_DETAIL_LENGTH ? json.substring(0, MAX_DETAIL_LENGTH) : json;
    }

    private void flush() {
        TransferTask update = new TransferTask();
        update.setId(task.getId());
        update.setSuccessCount(successCount);
        update.setFailCount(failCount);
        update.setUpdateTime(LocalDateTime.now());
        mapper.updateById(update);
    }

    /**
     * 单条失败明细。
     *
     * @param name   文件名称
     * @param reason 失败原因
     */
    public record FailItem(String name, String reason) {
    }
}
