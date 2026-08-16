package com.fleyx.jcloud.service;

import com.fleyx.jcloud.service.support.HwaccelProbeExecutor;
import com.fleyx.jcloud.service.support.HwaccelProbeSupport;
import com.fleyx.jcloud.service.support.TranscodeConfigResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 硬解启动探测编排测试：经真实 SystemConfigService 验证优先级、落库、不重探、auto 覆写。
 * <p>
 * 用 {@link TestConfiguration} 注入可编程各档可用性的假 {@link HwaccelProbeExecutor}，
 * 不触发真实 ffmpeg 进程（无 GPU 的 CI 可跑）；启动 Runner 因测试配置关闭探测而不参与。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HwaccelProbeSupportTest {

    @TestConfiguration
    static class FakeProbeExecutorConfig {

        @Bean
        @Primary
        HwaccelProbeExecutor fakeHwaccelProbeExecutor() {
            return new FakeProbeExecutor();
        }
    }

    /**
     * 可编程各档可用性的假执行器，并记录探测调用顺序。
     */
    static class FakeProbeExecutor implements HwaccelProbeExecutor {

        private final Map<String, Boolean> availability = new HashMap<>();
        private final List<String> calls = new ArrayList<>();

        void setAvailable(String hwaccel, boolean available) {
            availability.put(hwaccel, available);
        }

        void reset() {
            availability.clear();
            calls.clear();
        }

        @Override
        public boolean probe(String hwaccel, String device) {
            calls.add(hwaccel);
            return availability.getOrDefault(hwaccel, false);
        }
    }

    @Autowired
    private HwaccelProbeSupport hwaccelProbeSupport;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private FakeProbeExecutor fakeProbeExecutor;

    @BeforeEach
    void resetFake() {
        // Spring 单例 Bean 的状态跨测试方法保留，每例清空假执行器的可用性与调用记录
        fakeProbeExecutor.reset();
    }

    @Test
    void shouldPickHighestPriorityWhenAllAvailable() {
        fakeProbeExecutor.setAvailable("nvenc", true);
        fakeProbeExecutor.setAvailable("qsv", true);
        fakeProbeExecutor.setAvailable("vaapi", true);

        hwaccelProbeSupport.probeAndPersistIfNeeded();

        assertEquals("nvenc", systemConfigService.getValue(TranscodeConfigResolver.CONFIG_KEY_HWACCEL, ""));
        // 首档成功后即短路，不再探测后续档
        assertEquals(List.of("nvenc"), fakeProbeExecutor.calls);
    }

    @Test
    void shouldPickFirstAvailableWhenHighestUnavailable() {
        fakeProbeExecutor.setAvailable("nvenc", false);
        fakeProbeExecutor.setAvailable("qsv", true);
        fakeProbeExecutor.setAvailable("vaapi", true);

        hwaccelProbeSupport.probeAndPersistIfNeeded();

        assertEquals("qsv", systemConfigService.getValue(TranscodeConfigResolver.CONFIG_KEY_HWACCEL, ""));
        assertEquals(List.of("nvenc", "qsv"), fakeProbeExecutor.calls);
    }

    @Test
    void shouldPersistNoneWhenAllUnavailable() {
        fakeProbeExecutor.setAvailable("nvenc", false);
        fakeProbeExecutor.setAvailable("qsv", false);
        fakeProbeExecutor.setAvailable("vaapi", false);

        hwaccelProbeSupport.probeAndPersistIfNeeded();

        assertEquals("none", systemConfigService.getValue(TranscodeConfigResolver.CONFIG_KEY_HWACCEL, ""));
        assertEquals(List.of("nvenc", "qsv", "vaapi"), fakeProbeExecutor.calls);
    }

    @Test
    void shouldSkipProbingWhenConcreteValueConfigured() {
        systemConfigService.setValue(TranscodeConfigResolver.CONFIG_KEY_HWACCEL, "qsv");
        fakeProbeExecutor.setAvailable("nvenc", true);

        hwaccelProbeSupport.probeAndPersistIfNeeded();

        // 已有具体配置：不探测、配置不被改动
        assertEquals("qsv", systemConfigService.getValue(TranscodeConfigResolver.CONFIG_KEY_HWACCEL, ""));
        assertTrue(fakeProbeExecutor.calls.isEmpty());
    }

    @Test
    void shouldSkipProbingWhenNoneConfigured() {
        systemConfigService.setValue(TranscodeConfigResolver.CONFIG_KEY_HWACCEL, "none");
        fakeProbeExecutor.setAvailable("nvenc", true);

        hwaccelProbeSupport.probeAndPersistIfNeeded();

        assertEquals("none", systemConfigService.getValue(TranscodeConfigResolver.CONFIG_KEY_HWACCEL, ""));
        assertTrue(fakeProbeExecutor.calls.isEmpty());
    }

    @Test
    void shouldProbeAndOverwriteWhenStoredAuto() {
        systemConfigService.setValue(TranscodeConfigResolver.CONFIG_KEY_HWACCEL, "auto");
        fakeProbeExecutor.setAvailable("nvenc", false);
        fakeProbeExecutor.setAvailable("qsv", true);

        hwaccelProbeSupport.probeAndPersistIfNeeded();

        // 存量 auto 视为未配置，触发一次探测并被覆写为具体档位
        assertEquals("qsv", systemConfigService.getValue(TranscodeConfigResolver.CONFIG_KEY_HWACCEL, ""));
        assertEquals(List.of("nvenc", "qsv"), fakeProbeExecutor.calls);
    }

    @Test
    void shouldProbeAndOverwriteWhenStoredAutoAllUnavailable() {
        systemConfigService.setValue(TranscodeConfigResolver.CONFIG_KEY_HWACCEL, "auto");
        fakeProbeExecutor.setAvailable("nvenc", false);
        fakeProbeExecutor.setAvailable("qsv", false);
        fakeProbeExecutor.setAvailable("vaapi", false);

        hwaccelProbeSupport.probeAndPersistIfNeeded();

        assertEquals("none", systemConfigService.getValue(TranscodeConfigResolver.CONFIG_KEY_HWACCEL, ""));
        assertEquals(List.of("nvenc", "qsv", "vaapi"), fakeProbeExecutor.calls);
    }
}
