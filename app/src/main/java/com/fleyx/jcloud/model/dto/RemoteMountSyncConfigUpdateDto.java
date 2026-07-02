package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 更新远程挂载同步配置 DTO。
 */
@Data
public class RemoteMountSyncConfigUpdateDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "挂载 ID 不能为空")
    private String remoteMountId;

    @NotBlank(message = "cron 表达式不能为空")
    @Size(max = 128, message = "cron 表达式长度不能超过 128")
    private String cronExpr;

    @NotNull(message = "启用状态不能为空")
    private Integer enabled;
}
