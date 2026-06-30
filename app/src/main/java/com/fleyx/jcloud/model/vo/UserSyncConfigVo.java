package com.fleyx.jcloud.model.vo;

import lombok.Data;

/**
 * 用户存储空间同步配置视图。
 */
@Data
public class UserSyncConfigVo {

    /**
     * 用户 ID。
     */
    private String userId;

    /**
     * 定时同步 cron 表达式。
     */
    private String cronExpr;

    /**
     * 是否启用定时同步：1 启用，0 禁用。
     */
    private Integer enabled;

    /**
     * 下次定时同步时间。
     */
    private String nextSyncTime;

    /**
     * 创建时间。
     */
    private String createTime;

    /**
     * 更新时间。
     */
    private String updateTime;
}
