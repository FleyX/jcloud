package com.fleyx.jcloud.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.text.SimpleDateFormat;
import java.util.TimeZone;

/**
 * Jackson 全局配置（适配 Spring Boot 4 默认的 Jackson 3）。
 */
@Configuration
public class JacksonConfig {

    /**
     * 自定义 JsonMapper，统一日期格式与时区。
     */
    @Bean
    @Primary
    public JsonMapper jsonMapper() {
        return JsonMapper.builder()
                .addModule(longToStringModule())
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .defaultDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"))
                .defaultTimeZone(TimeZone.getTimeZone("GMT+8"))
                .build();
    }

    /**
     * 将 Long / long 类型序列化为字符串，避免前端 JavaScript 数字精度丢失。
     */
    @Bean
    public SimpleModule longToStringModule() {
        SimpleModule module = new SimpleModule("long-to-string");
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        return module;
    }
}
