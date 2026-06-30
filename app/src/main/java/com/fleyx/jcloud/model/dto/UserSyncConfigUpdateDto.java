package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户存储空间同步配置更新 DTO。
 */
@Data
public class UserSyncConfigUpdateDto {

    /**
     * 用户 ID。
     */
    private String userId;

    /**
     * 定时同步 cron 表达式。
     */
    @NotBlank(message = "cron 表达式不能为空")
    private String cronExpr;

    /**
     * 是否启用定时同步：1 启用，0 禁用。
     */
    @NotNull(message = "启用状态不能为空")
    private Integer enabled;
}
