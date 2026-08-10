package com.fleyx.jcloud.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 连接与读写可用性测试。
 * <p>
 * 项目未提供自定义 Redis 配置类，依赖 Spring Boot 自动装配；
 * 这里通过一次真实的 set/get 往返验证自动装配的模板可用（test profile 直连 dev.lan 的 Redis）。
 */
@SpringBootTest
@ActiveProfiles("test")
class RedisConfigTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Test
    void redisTemplateShouldSupportSetGetRoundTrip() {
        assertThat(stringRedisTemplate).isNotNull();
        assertThat(redisConnectionFactory).isNotNull();
        assertThat(stringRedisTemplate.getConnectionFactory()).isEqualTo(redisConnectionFactory);

        String key = "test:redis-config:" + UUID.randomUUID();
        try {
            stringRedisTemplate.opsForValue().set(key, "hello-redis");
            assertThat(stringRedisTemplate.opsForValue().get(key)).isEqualTo("hello-redis");
        } finally {
            stringRedisTemplate.delete(key);
        }
    }
}
