package com.fleyx.jcloud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 鉴权会话配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jcloud.auth")
public class AuthProperties {

    /**
     * 刷新令牌有效期（天），滑动续期。
     */
    private long refreshExpireDays = 30;

    /**
     * 令牌轮换宽限期（秒），用于容忍并发/重试重放。
     */
    private long rotationGraceSeconds = 30;
}
