package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户存储空间绑定 DTO。
 */
@Data
public class UserStorageDto {

    /**
     * 用户 ID。
     */
    @NotNull(message = "用户 ID 不能为空")
    private Long userId;

    /**
     * 默认存储空间 ID。
     */
    @NotNull(message = "存储空间 ID 不能为空")
    private Long storageSpaceId;

    /**
     * 用户配额（字节），0 表示不限制。
     */
    @NotNull(message = "配额不能为空")
    @Min(value = 0, message = "配额不能为负数")
    private Long quota;
}
