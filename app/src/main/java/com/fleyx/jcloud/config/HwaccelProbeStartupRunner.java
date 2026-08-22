package com.fleyx.jcloud.config;

import com.fleyx.jcloud.service.support.HwaccelProbeSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 应用启动时执行硬解方式探测并落库（仅当配置开关开启）。
 * <p>
 * 任何异常仅 warn 日志，不阻断应用启动（探测失败时配置表保持未配置，
 * 编码器选择走 yml 兜底软解，见 {@code TranscodeCommandBuilder.selectEncoder}）。
 */
@Slf4j
@Component
@Order(200)
@RequiredArgsConstructor
public class HwaccelProbeStartupRunner implements ApplicationRunner {

    private final MediaProperties mediaProperties;
    private final HwaccelProbeSupport hwaccelProbeSupport;

    @Override
    public void run(ApplicationArguments args) {
        if (!mediaProperties.isHwaccelProbeEnabled()) {
            log.debug("硬解启动探测已禁用（jcloud.media.hwaccel-probe-enabled=false），跳过");
            return;
        }
        try {
            hwaccelProbeSupport.probeAndPersistIfNeeded();
        } catch (Exception e) {
            log.warn("硬解启动探测异常，跳过本次探测: {}", e.getMessage());
        }
    }
}
