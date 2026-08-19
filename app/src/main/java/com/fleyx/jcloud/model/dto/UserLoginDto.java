package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户登录 DTO。
 */
@Data
public class UserLoginDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "用户名不能为空")
    @Size(max = 64, message = "用户名长度不能超过 64")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 5, max = 128, message = "密码长度需在 5-128 之间")
    private String password;

    /**
     * 设备标识（可选，空白由服务端生成）。
     */
    @Size(max = 64, message = "设备标识长度不能超过 64")
    private String deviceId;

    /**
     * 设备名（可选，非空时优先采用）。
     */
    @Size(max = 64, message = "设备名长度不能超过 64")
    private String deviceName;
}
