package com.fleyx.jcloud.service.support;

import cn.hutool.core.util.StrUtil;
import com.fleyx.jcloud.model.bo.SmtpConfig;
import com.fleyx.jcloud.model.dto.SmtpConfigDto;
import com.fleyx.jcloud.service.SystemConfigService;
import com.fleyx.jcloud.util.RemoteConfigCrypto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 发件邮箱（SMTP）配置支撑类：集中持有系统配置键与读写逻辑。
 * <p>
 * 密码复用 {@link RemoteConfigCrypto} 加密后存储于系统配置表，读取时解密。
 */
@Component
@RequiredArgsConstructor
public class SmtpConfigSupport {

    /**
     * 系统配置键：SMTP 主机。
     */
    public static final String CONFIG_KEY_HOST = "notification.smtp.host";

    /**
     * 系统配置键：SMTP 端口。
     */
    public static final String CONFIG_KEY_PORT = "notification.smtp.port";

    /**
     * 系统配置键：SMTP 账号。
     */
    public static final String CONFIG_KEY_USERNAME = "notification.smtp.username";

    /**
     * 系统配置键：SMTP 密码密文。
     */
    public static final String CONFIG_KEY_PASSWORD = "notification.smtp.password";

    /**
     * 系统配置键：加密方式。
     */
    public static final String CONFIG_KEY_ENCRYPTION = "notification.smtp.encryption";

    /**
     * 系统配置键：发件人地址。
     */
    public static final String CONFIG_KEY_FROM_ADDRESS = "notification.smtp.from-address";

    /**
     * 系统配置键：发件人昵称。
     */
    public static final String CONFIG_KEY_FROM_NAME = "notification.smtp.from-name";

    /**
     * 系统配置键：邮件分享附件直发大小上限（MB）。
     */
    public static final String CONFIG_KEY_ATTACHMENT_MAX_SIZE = "notification.email-share.attachment-max-size";

    /**
     * 附件直发大小上限默认值（MB）。
     */
    public static final int DEFAULT_ATTACHMENT_MAX_SIZE_MB = 50;

    /**
     * 加密方式：不加密。
     */
    public static final String ENCRYPTION_NONE = "none";

    /**
     * 加密方式：SSL。
     */
    public static final String ENCRYPTION_SSL = "ssl";

    /**
     * 加密方式：STARTTLS。
     */
    public static final String ENCRYPTION_STARTTLS = "starttls";

    /**
     * 加密方式默认值。
     */
    public static final String DEFAULT_ENCRYPTION = ENCRYPTION_SSL;

    /**
     * 端口默认值（SSL 常用端口）。
     */
    public static final int DEFAULT_PORT = 465;

    private static final Set<String> ENCRYPTIONS =
            Set.of(ENCRYPTION_NONE, ENCRYPTION_SSL, ENCRYPTION_STARTTLS);

    private final SystemConfigService systemConfigService;
    private final RemoteConfigCrypto remoteConfigCrypto;

    /**
     * 读取当前发件邮箱配置，密码解密返回明文。
     */
    public SmtpConfig load() {
        SmtpConfig config = new SmtpConfig();
        config.setHost(blankToNull(systemConfigService.getValue(CONFIG_KEY_HOST, "")));
        config.setPort(resolvePort(systemConfigService.getValue(CONFIG_KEY_PORT, "")));
        config.setUsername(blankToNull(systemConfigService.getValue(CONFIG_KEY_USERNAME, "")));
        String cipher = systemConfigService.getValue(CONFIG_KEY_PASSWORD, "");
        config.setPassword(StrUtil.isBlank(cipher) ? null : remoteConfigCrypto.decrypt(cipher));
        config.setEncryption(resolveEncryption(systemConfigService.getValue(CONFIG_KEY_ENCRYPTION, "")));
        config.setFromAddress(blankToNull(systemConfigService.getValue(CONFIG_KEY_FROM_ADDRESS, "")));
        config.setFromName(blankToNull(systemConfigService.getValue(CONFIG_KEY_FROM_NAME, "")));
        return config;
    }

    /**
     * 保存发件邮箱配置；密码为空时保留原密码。
     */
    public void save(SmtpConfigDto dto) {
        systemConfigService.setValue(CONFIG_KEY_HOST, trim(dto.getHost()));
        systemConfigService.setValue(CONFIG_KEY_PORT, String.valueOf(dto.getPort()));
        systemConfigService.setValue(CONFIG_KEY_USERNAME, trim(dto.getUsername()));
        if (StrUtil.isNotBlank(dto.getPassword())) {
            systemConfigService.setValue(CONFIG_KEY_PASSWORD, remoteConfigCrypto.encrypt(dto.getPassword()));
        }
        systemConfigService.setValue(CONFIG_KEY_ENCRYPTION, dto.getEncryption());
        systemConfigService.setValue(CONFIG_KEY_FROM_ADDRESS, trim(dto.getFromAddress()));
        systemConfigService.setValue(CONFIG_KEY_FROM_NAME, trim(dto.getFromName()));
        if (dto.getAttachmentMaxSizeMb() != null) {
            systemConfigService.setValue(CONFIG_KEY_ATTACHMENT_MAX_SIZE,
                    String.valueOf(dto.getAttachmentMaxSizeMb()));
        }
    }

    /**
     * 是否已配置发件邮箱。
     */
    public boolean isConfigured() {
        return load().isConfigured();
    }

    /**
     * 读取邮件分享附件直发大小上限（MB），未配置或非法时回退默认值。
     */
    public int attachmentMaxSizeMb() {
        try {
            int value = Integer.parseInt(systemConfigService.getValue(CONFIG_KEY_ATTACHMENT_MAX_SIZE, "").trim());
            return value > 0 ? value : DEFAULT_ATTACHMENT_MAX_SIZE_MB;
        } catch (NumberFormatException e) {
            return DEFAULT_ATTACHMENT_MAX_SIZE_MB;
        }
    }

    private int resolvePort(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    private String resolveEncryption(String value) {
        return ENCRYPTIONS.contains(value) ? value : DEFAULT_ENCRYPTION;
    }

    private String blankToNull(String value) {
        return StrUtil.isBlank(value) ? null : value.trim();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
