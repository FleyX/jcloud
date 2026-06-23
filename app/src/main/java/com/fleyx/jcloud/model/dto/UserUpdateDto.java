package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 用户更新 DTO。
 */
@Data
public class UserUpdateDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID。
     */
    private Long id;

    /**
     * 昵称。
     */
    @Size(max = 64, message = "昵称长度不能超过 64")
    private String nickname;

    /**
     * 邮箱。
     */
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过 128")
    private String email;

    /**
     * 角色 ID 列表。
     */
    private List<Long> roleIds;

    /**
     * 状态：1 启用，0 禁用。
     */
    private Integer status;

    /**
     * 密码，为空表示不修改。
     */
    @Size(min = 6, max = 128, message = "密码长度需在 6-128 之间")
    private String password;
}
