package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 存储空间扩容 DTO。
 */
@Data
public class StorageSpaceExpandDto {

    /**
     * 存储空间 ID。
     */
    @NotNull(message = "存储空间 ID 不能为空")
    private Long id;

    /**
     * 新容量（字节）。
     */
    @NotNull(message = "容量不能为空")
    @Min(value = 1, message = "容量必须大于 0")
    private Long capacity;
}
