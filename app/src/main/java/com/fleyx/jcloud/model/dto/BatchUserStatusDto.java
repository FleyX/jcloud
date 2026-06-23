package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 批量修改用户状态 DTO。
 */
@Data
public class BatchUserStatusDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID 列表。
     */
    @NotEmpty(message = "用户 ID 列表不能为空")
    private List<Long> userIds;

    /**
     * 状态：1 启用，0 禁用。
     */
    @NotNull(message = "状态不能为空")
    private Integer status;
}
