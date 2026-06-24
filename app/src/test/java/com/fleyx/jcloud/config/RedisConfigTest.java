package com.fleyx.jcloud.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 配置加载测试。
 */
@SpringBootTest
@ActiveProfiles("test")
class RedisConfigTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Test
    void redisTemplateShouldBeConfigured() {
        assertThat(stringRedisTemplate).isNotNull();
        assertThat(redisConnectionFactory).isNotNull();
        assertThat(stringRedisTemplate.getConnectionFactory()).isEqualTo(redisConnectionFactory);
    }
}
