package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class PermissionStatusDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "状态不能为空")
    private Integer status;
}
