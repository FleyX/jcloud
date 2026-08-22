package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 硬解方式启动探测编排：未配置（无记录或空值）或存量 auto 记录时，
 * 按 NVENC &gt; QSV &gt; VAAPI 优先级逐档做真实编码探测，首个可用档（全无则 none 软解）
 * 落库并打 info 日志；已有具体档位（nvenc/qsv/vaapi/none）则不探测、不改动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HwaccelProbeSupport {

    /**
     * 探测顺序即优先级：NVENC &gt; QSV &gt; VAAPI。
     */
    private static final List<String> CANDIDATES = List.of("nvenc", "qsv", "vaapi");

    /**
     * 已有具体档位集合，命中即跳过探测。
     */
    private static final Set<String> CONCRETE_VALUES = Set.of("nvenc", "qsv", "vaapi", "none");

    private final SystemConfigService systemConfigService;
    private final HwaccelProbeExecutor probeExecutor;

    /**
     * 按需执行一次硬解探测并落库；具体档位已配置时直接返回。
     */
    public void probeAndPersistIfNeeded() {
        String configured = systemConfigService.getValue(TranscodeConfigResolver.CONFIG_KEY_HWACCEL, "");
        if (configured != null && !configured.isBlank()) {
            String normalized = configured.trim().toLowerCase(Locale.ROOT);
            if (CONCRETE_VALUES.contains(normalized)) {
                log.debug("硬解配置已确定为 {}，跳过启动探测", normalized);
                return;
            }
            if ("auto".equals(normalized)) {
                log.debug("存量硬解配置为 auto，视为未配置，触发启动探测");
            } else {
                log.debug("存量硬解配置 {} 非合法档位，视为未配置，触发启动探测", normalized);
            }
        }
        for (String candidate : CANDIDATES) {
            if (probeExecutor.probe(candidate, null)) {
                systemConfigService.setValue(TranscodeConfigResolver.CONFIG_KEY_HWACCEL, candidate);
                log.info("硬解启动探测完成：选中 {}（NVENC > QSV > VAAPI 优先级首个可用）", candidate);
                return;
            }
        }
        systemConfigService.setValue(TranscodeConfigResolver.CONFIG_KEY_HWACCEL, "none");
        log.info("硬解启动探测完成：无可用硬件编码器，转码回退软解（none）");
    }
}
