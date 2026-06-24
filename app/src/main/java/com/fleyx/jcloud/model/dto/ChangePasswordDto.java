package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 修改当前登录用户密码 DTO。
 */
@Data
public class ChangePasswordDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 当前密码。
     */
    @NotBlank(message = "当前密码不能为空")
    private String currentPassword;

    /**
     * 新密码。
     */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, message = "新密码长度不能少于6位")
    private String newPassword;

    /**
     * 确认新密码。
     */
    @NotBlank(message = "确认新密码不能为空")
    private String confirmPassword;
}
