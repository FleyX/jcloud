package com.fleyx.jcloud;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 应用上下文加载测试。
 */
@SpringBootTest
@ActiveProfiles("test")
class JcloudApplicationTests {

    @Test
    void contextLoads() {
        // 上下文加载成功即通过测试
    }
}
