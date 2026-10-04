package com.fleyx.jcloud.model.bo;

import lombok.Data;

/**
 * 发件邮箱（SMTP）配置业务对象，密码为解密后的明文，仅内部使用。
 */
@Data
public class SmtpConfig {

    /**
     * SMTP 主机。
     */
    private String host;

    /**
     * SMTP 端口。
     */
    private Integer port;

    /**
     * SMTP 账号，可为空（免认证）。
     */
    private String username;

    /**
     * SMTP 密码明文。
     */
    private String password;

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
     * 是否已配置：主机与发件人地址均非空即可发信。
     */
    public boolean isConfigured() {
        return host != null && !host.isBlank() && fromAddress != null && !fromAddress.isBlank();
    }
}
