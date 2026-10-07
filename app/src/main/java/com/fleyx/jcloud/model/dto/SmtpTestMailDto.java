package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 测试邮件发送入参。
 */
@Data
public class SmtpTestMailDto {

    /**
     * 收件人地址。
     */
    @NotBlank(message = "收件人地址不能为空")
    @Email(message = "收件人地址格式不正确")
    private String to;
}
