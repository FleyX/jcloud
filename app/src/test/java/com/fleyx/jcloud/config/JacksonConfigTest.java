package com.fleyx.jcloud.config;

import com.fleyx.jcloud.model.vo.MediaItemVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主 JsonMapper 的 LocalDateTime 序列化回归测试（issue 02）。
 * 契约：java.time.LocalDateTime 输出为无 T、无小数秒的 yyyy-MM-dd HH:mm:ss 字符串，
 * 保证影视首页 addedTime 与前端解析约定一致。
 */
@SpringBootTest
@ActiveProfiles("test")
class JacksonConfigTest {

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void localDateTimeWithNanosShouldSerializeAsWallClockWithoutSeparatorOrFraction() throws Exception {
        MediaItemVo vo = new MediaItemVo();
        vo.setAddedTime(LocalDateTime.of(2023, 3, 4, 10, 0, 0, 123456789));

        String json = jsonMapper.writeValueAsString(vo);

        assertThat(json).contains("\"addedTime\":\"2023-03-04 10:00:00\"");
        assertThat(json).doesNotContain("2023-03-04T");
        assertThat(json).doesNotContain("10:00:00.123");
    }

    @Test
    void nullFieldsShouldKeepSerializingAsNullUnchangedByModule() throws Exception {
        // 自定义主 mapper 不套用 spring.jackson.default-property-inclusion: non_null，
        // null 字段实际以 "field":null 输出；本用例保证新注册的 LocalDateTime 模块不改变既有 null 行为。
        MediaItemVo vo = new MediaItemVo();

        String json = jsonMapper.writeValueAsString(vo);

        assertThat(json).contains("\"addedTime\":null");
        assertThat(json).contains("\"lastPlayTime\":null");
    }
}
