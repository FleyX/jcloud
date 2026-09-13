package com.fleyx.jcloud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

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

    /**
     * cookie 是否启用 Secure。默认关闭以兼容局域网纯 HTTP 环境，HTTPS 部署时应开启。
     */
    private boolean cookieSecure = false;

    /**
     * 是否开放公开注册。默认关闭，受邀用户由管理员在用户管理中手动建号。
     */
    private boolean registrationEnabled = false;

    /**
     * 登录限流配置（失败锁定与 IP 维度限额）。
     */
    private RateLimit rateLimit = new RateLimit();

    /**
     * 登录限流配置组（{@code jcloud.auth.rate-limit.*}）。
     */
    @Data
    public static class RateLimit {

        /**
         * 同一主体连续登录失败次数阈值，达到即锁定。
         */
        private int maxFailures = 5;

        /**
         * 锁定时长，锁定期间即使密码正确也拒绝登录。
         */
        private Duration lockDuration = Duration.ofMinutes(15);

        /**
         * 同一真实客户端 IP 每分钟允许的认证请求数上限。
         */
        private int ipMaxPerMinute = 20;
    }
}
