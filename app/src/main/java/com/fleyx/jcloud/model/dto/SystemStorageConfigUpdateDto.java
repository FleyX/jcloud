package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 系统数据目录配置更新 DTO。
 */
@Data
public class SystemStorageConfigUpdateDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 指定的系统数据目录所在用户存储空间 ID。
     */
    @NotBlank(message = "存储空间 ID 不能为空")
    private String systemSpaceId;
}
