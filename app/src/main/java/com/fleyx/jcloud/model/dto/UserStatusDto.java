package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户状态变更 DTO。
 */
@Data
public class UserStatusDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String userId;

    @NotNull(message = "状态不能为空")
    private Integer status;
}
