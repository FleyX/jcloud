package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.config.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AuthRateLimitSupport 集成测试（真实 Redis，test 配置的 database 1）。
 * <p>
 * 每个用例使用唯一主体名与独立 IP（运行级 /16 前缀），避免 Redis 计数跨用例、跨运行串扰。
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthRateLimitSupportTest {

    /**
     * 运行级随机 /16 前缀，隔离历史运行残留的 IP 窗口计数。
     */
    private static final int RUN_MARKER = Integer.parseInt(
            UUID.randomUUID().toString().replace("-", "").substring(0, 4), 16);
    private static final String LOCKED_MESSAGE = "尝试次数过多，请稍后再试";

    @Autowired
    private AuthRateLimitSupport authRateLimitSupport;

    @Autowired
    private AuthProperties authProperties;

    private static String uniqueSubject() {
        return "rlsub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String ip(int lastOctet) {
        return "10." + (RUN_MARKER / 256) + "." + (RUN_MARKER % 256) + "." + lastOctet;
    }

    @Test
    void subjectShouldBeAllowedBeforeThreshold() {
        String subject = uniqueSubject();
        int maxFailures = authProperties.getRateLimit().getMaxFailures();
        for (int i = 0; i < maxFailures - 1; i++) {
            authRateLimitSupport.recordLoginFailure(subject);
        }
        assertDoesNotThrow(() -> authRateLimitSupport.assertLoginAllowed(subject, ip(1)));
    }

    @Test
    void failuresReachingThresholdShouldLockSubject() {
        String subject = uniqueSubject();
        int maxFailures = authProperties.getRateLimit().getMaxFailures();
        for (int i = 0; i < maxFailures; i++) {
            authRateLimitSupport.recordLoginFailure(subject);
        }
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authRateLimitSupport.assertLoginAllowed(subject, ip(2)));
        assertEquals(LOCKED_MESSAGE, ex.getMessage());
        assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getResultCode().getCode());
    }

    @Test
    void lockShouldExpireAfterLockDuration() throws InterruptedException {
        String subject = uniqueSubject();
        AuthProperties.RateLimit rateLimit = authProperties.getRateLimit();
        Duration original = rateLimit.getLockDuration();
        rateLimit.setLockDuration(Duration.ofSeconds(1));
        try {
            int maxFailures = rateLimit.getMaxFailures();
            for (int i = 0; i < maxFailures; i++) {
                authRateLimitSupport.recordLoginFailure(subject);
            }
            assertThrows(BusinessException.class,
                    () -> authRateLimitSupport.assertLoginAllowed(subject, ip(3)));
            Thread.sleep(1200);
            assertDoesNotThrow(() -> authRateLimitSupport.assertLoginAllowed(subject, ip(3)));
        } finally {
            rateLimit.setLockDuration(original);
        }
    }

    @Test
    void successShouldClearFailureCount() {
        String subject = uniqueSubject();
        int maxFailures = authProperties.getRateLimit().getMaxFailures();
        for (int i = 0; i < maxFailures - 1; i++) {
            authRateLimitSupport.recordLoginFailure(subject);
        }
        authRateLimitSupport.recordLoginSuccess(subject);
        for (int i = 0; i < maxFailures - 1; i++) {
            authRateLimitSupport.recordLoginFailure(subject);
        }
        // 若成功未清零，累计失败数已达阈值，此处会被锁定
        assertDoesNotThrow(() -> authRateLimitSupport.assertLoginAllowed(subject, ip(4)));
    }

    @Test
    void ipExceedingPerMinuteLimitShouldBeRejected() {
        String subject = uniqueSubject();
        String ip = ip(5);
        int limit = authProperties.getRateLimit().getIpMaxPerMinute();
        for (int i = 0; i < limit; i++) {
            int ignored = i;
            assertDoesNotThrow(() -> authRateLimitSupport.assertLoginAllowed(subject + ignored, ip));
        }
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authRateLimitSupport.assertLoginAllowed(subject, ip));
        assertEquals(LOCKED_MESSAGE, ex.getMessage());
    }

    @Test
    void ipCountsShouldBeIsolatedPerIp() {
        String subject = uniqueSubject();
        String ipA = ip(6);
        String ipB = ip(7);
        int limit = authProperties.getRateLimit().getIpMaxPerMinute();
        for (int i = 0; i < limit; i++) {
            int ignored = i;
            assertDoesNotThrow(() -> authRateLimitSupport.assertLoginAllowed(subject + ignored, ipA));
        }
        assertThrows(BusinessException.class, () -> authRateLimitSupport.assertLoginAllowed(subject, ipA));
        assertDoesNotThrow(() -> authRateLimitSupport.assertLoginAllowed(subject, ipB));
    }
}
