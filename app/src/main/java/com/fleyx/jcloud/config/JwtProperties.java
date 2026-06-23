package com.fleyx.jcloud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jcloud.jwt")
public class JwtProperties {

    /**
     * 签名密钥。
     */
    private String secret;

    /**
     * 过期时间（小时）。
     */
    private long expireHours = 24;

    /**
     * 签发人。
     */
    private String issuer = "jcloud";
}
