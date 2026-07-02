package com.fleyx.jcloud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 远程挂载相关配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jcloud.remote")
public class RemoteProperties {

    /**
     * 远程挂载凭据加密密钥。
     */
    private String encryptionKey;
}
