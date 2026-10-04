package com.fleyx.jcloud.model.vo;

import lombok.Data;

/**
 * 发件邮箱（SMTP）配置视图，密码不回显，仅以 hasPassword 标识是否已设置。
 */
@Data
public class SmtpConfigVo {

    /**
     * SMTP 主机。
     */
    private String host;

    /**
     * SMTP 端口。
     */
    private Integer port;

    /**
     * SMTP 账号。
     */
    private String username;

    /**
     * 加密方式：none / ssl / starttls。
     */
    private String encryption;

    /**
     * 发件人地址。
     */
    private String fromAddress;

    /**
     * 发件人昵称。
     */
    private String fromName;

    /**
     * 是否已设置密码。
     */
    private boolean hasPassword;
}
