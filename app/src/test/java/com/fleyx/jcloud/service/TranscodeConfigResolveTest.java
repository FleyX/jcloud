package com.fleyx.jcloud.service;

import com.fleyx.jcloud.service.support.TranscodeConfigResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 转码全局配置解析测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TranscodeConfigResolveTest {

    @Autowired
    private TranscodeConfigResolver transcodeConfigResolver;

    @Autowired
    private SystemConfigService systemConfigService;

    @Test
    void shouldFallbackToDefaultWhenNotConfigured() {
        assertEquals("auto", transcodeConfigResolver.resolveHwaccel());
        assertEquals("/dev/dri/renderD128", transcodeConfigResolver.resolveDevice());
        assertEquals(0, transcodeConfigResolver.resolveThreads());
    }

    @Test
    void shouldResolveConfiguredValues() {
        systemConfigService.setValue(TranscodeConfigResolver.CONFIG_KEY_HWACCEL, "qsv");
        systemConfigService.setValue(TranscodeConfigResolver.CONFIG_KEY_DEVICE, "/dev/dri/renderD129");
        systemConfigService.setValue(TranscodeConfigResolver.CONFIG_KEY_THREADS, "4");

        assertEquals("qsv", transcodeConfigResolver.resolveHwaccel());
        assertEquals("/dev/dri/renderD129", transcodeConfigResolver.resolveDevice());
        assertEquals(4, transcodeConfigResolver.resolveThreads());
    }

    @Test
    void shouldTolerateInvalidThreadsValue() {
        systemConfigService.setValue(TranscodeConfigResolver.CONFIG_KEY_THREADS, "abc");
        assertEquals(0, transcodeConfigResolver.resolveThreads());

        systemConfigService.setValue(TranscodeConfigResolver.CONFIG_KEY_THREADS, "-2");
        assertEquals(0, transcodeConfigResolver.resolveThreads());
    }
}
