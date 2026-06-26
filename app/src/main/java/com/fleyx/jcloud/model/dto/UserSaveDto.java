package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户保存/新增 DTO。
 */
@Data
public class UserSaveDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户名。
     */
    @NotBlank(message = "用户名不能为空")
    @Size(max = 64, message = "用户名长度不能超过 64")
    private String username;

    /**
     * 密码。
     */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 128, message = "密码长度需在 6-128 之间")
    private String password;

    /**
     * 邮箱。
     */
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过 128")
    private String email;

    /**
     * 昵称。
     */
    @Size(max = 64, message = "昵称长度不能超过 64")
    private String nickname;

    /**
     * 默认存储空间 ID。
     */
    @NotNull(message = "默认存储空间不能为空")
    private Long storageSpaceId;

    /**
     * 用户配额数值。
     */
    @NotNull(message = "配额不能为空")
    private Long quota;

    /**
     * 配额单位：MB / GB / TB，为空时默认 GB。
     */
    @Size(max = 8, message = "配额单位长度不能超过 8")
    private String quotaUnit;
}
