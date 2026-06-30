package com.fleyx.jcloud.model.vo;

import lombok.Data;

/**
 * 用户存储空间同步任务视图。
 */
@Data
public class UserSyncTaskVo {

    /**
     * 任务 ID。
     */
    private String id;

    /**
     * 用户 ID。
     */
    private String userId;

    /**
     * 触发方式：manual / scheduled。
     */
    private String type;

    /**
     * 任务状态。
     */
    private String status;

    /**
     * 任务开始时间。
     */
    private String startTime;

    /**
     * 任务结束时间。
     */
    private String endTime;

    /**
     * 扫描到的物理节点总数。
     */
    private Long totalCount;

    /**
     * 成功同步节点数。
     */
    private Long successCount;

    /**
     * 失败/跳过节点数。
     */
    private Long failCount;

    /**
     * 错误信息。
     */
    private String errorMsg;

    /**
     * 创建时间。
     */
    private String createTime;

    /**
     * 更新时间。
     */
    private String updateTime;
}
