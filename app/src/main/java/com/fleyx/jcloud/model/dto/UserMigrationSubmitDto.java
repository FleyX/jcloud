package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户存储空间迁移提交 DTO。
 */
@Data
public class UserMigrationSubmitDto {

    /**
     * 用户 ID。
     */
    @NotNull(message = "用户 ID 不能为空")
    private String userId;

    /**
     * 目标存储空间 ID。
     */
    @NotNull(message = "目标存储空间 ID 不能为空")
    private String targetSpaceId;

    /**
     * 新配额（字节）。
     */
    @NotNull(message = "新配额不能为空")
    @Min(value = 1, message = "新配额必须大于 0")
    private Long newQuota;
}
