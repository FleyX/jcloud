package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 修改用户角色 DTO。
 */
@Data
public class UserUpdateRolesDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;

    @NotEmpty(message = "角色 ID 列表不能为空")
    private List<Long> roleIds;
}
