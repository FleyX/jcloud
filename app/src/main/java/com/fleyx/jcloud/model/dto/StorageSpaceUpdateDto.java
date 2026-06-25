package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 存储空间更新 DTO。
 */
@Data
public class StorageSpaceUpdateDto {

    /**
     * ID。
     */
    @NotNull(message = "ID 不能为空")
    private Long id;

    /**
     * 存储空间名称。
     */
    @NotBlank(message = "存储空间名称不能为空")
    @Size(max = 64, message = "存储空间名称长度不能超过 64")
    private String name;

    /**
     * 物理路径。
     */
    @NotBlank(message = "物理路径不能为空")
    @Size(max = 512, message = "物理路径长度不能超过 512")
    private String path;

    /**
     * 类型：USER / SYSTEM。
     */
    @NotBlank(message = "类型不能为空")
    private String type;

    /**
     * 容量（字节）。
     */
    @NotNull(message = "容量不能为空")
    @Min(value = 1, message = "容量必须大于 0")
    private Long capacity;

    /**
     * 状态：1 启用，0 禁用。
     */
    @NotNull(message = "状态不能为空")
    private Integer status;

    /**
     * 备注。
     */
    @Size(max = 255, message = "备注长度不能超过 255")
    private String remark;
}
