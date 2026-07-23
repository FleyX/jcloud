package com.fleyx.jcloud.model.po;

import java.time.LocalDateTime;

/**
 * 同步任务通用字段契约。
 * <p>
 * 远程挂载同步任务与用户存储空间同步任务的 PO 结构同构，
 * 实现该接口后可复用 {@code SyncTaskSupport} 中的通用状态回写逻辑。
 */
public interface SyncTaskFields {

    void setId(String id);

    void setType(String type);

    void setStatus(String status);

    void setStartTime(LocalDateTime startTime);

    void setEndTime(LocalDateTime endTime);

    void setTotalCount(Long totalCount);

    void setSuccessCount(Long successCount);

    void setFailCount(Long failCount);

    void setErrorMsg(String errorMsg);

    void setUpdateTime(LocalDateTime updateTime);
}
