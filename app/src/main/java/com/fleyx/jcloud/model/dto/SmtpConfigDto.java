package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 发件邮箱（SMTP）配置入参。
 */
@Data
public class SmtpConfigDto {

    /**
     * SMTP 主机。
     */
    @NotBlank(message = "SMTP 主机不能为空")
    private String host;

    /**
     * SMTP 端口。
     */
    @NotNull(message = "SMTP 端口不能为空")
    @Min(value = 1, message = "SMTP 端口需在 1~65535 之间")
    @Max(value = 65535, message = "SMTP 端口需在 1~65535 之间")
    private Integer port;

    /**
     * SMTP 账号，可为空（免认证）。
     */
    private String username;

    /**
     * SMTP 密码明文；为空表示保留原密码。
     */
    private String password;

    /**
     * 加密方式：none / ssl / starttls。
     */
    @NotBlank(message = "加密方式不能为空")
    @Pattern(regexp = "none|ssl|starttls", message = "非法的加密方式")
    private String encryption;

    /**
     * 发件人地址。
     */
    @NotBlank(message = "发件人地址不能为空")
    @Email(message = "发件人地址格式不正确")
    private String fromAddress;

    /**
     * 发件人昵称。
     */
    private String fromName;
}
