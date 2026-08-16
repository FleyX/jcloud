package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.config.MediaProperties;
import com.fleyx.jcloud.service.SystemConfigService;
import org.springframework.stereotype.Component;

/**
 * 转码全局配置解析：优先系统配置表，缺省回退 yml 配置。
 */
@Component
public class TranscodeConfigResolver {

    /**
     * 系统配置键：硬件加速方式（vaapi / qsv / nvenc / none；auto 仅作为启动探测完成前的 yml 兜底，不落库）。
     */
    public static final String CONFIG_KEY_HWACCEL = "media.transcode.hwaccel";

    /**
     * 系统配置键：硬件设备路径（仅 vaapi 使用）。
     */
    public static final String CONFIG_KEY_DEVICE = "media.transcode.device";

    /**
     * 系统配置键：ffmpeg 单任务线程数，0 表示自动。
     */
    public static final String CONFIG_KEY_THREADS = "media.transcode.threads";

    private static final String DEFAULT_DEVICE = "/dev/dri/renderD128";

    private final MediaProperties mediaProperties;
    private final SystemConfigService systemConfigService;

    public TranscodeConfigResolver(MediaProperties mediaProperties, SystemConfigService systemConfigService) {
        this.mediaProperties = mediaProperties;
        this.systemConfigService = systemConfigService;
    }

    /**
     * 读取当前生效的硬解方式：优先系统配置表，缺省回退 yml 配置。
     */
    public String resolveHwaccel() {
        String configured = systemConfigService.getValue(CONFIG_KEY_HWACCEL, "");
        if (configured == null || configured.isBlank()) {
            configured = mediaProperties.getHwaccel();
        }
        return configured;
    }

    /**
     * 读取当前生效的硬件设备路径。
     */
    public String resolveDevice() {
        String device = systemConfigService.getValue(CONFIG_KEY_DEVICE, "");
        return device == null || device.isBlank() ? DEFAULT_DEVICE : device.trim();
    }

    /**
     * 读取当前生效的 ffmpeg 单任务线程数，0 表示自动。
     */
    public int resolveThreads() {
        String value = systemConfigService.getValue(CONFIG_KEY_THREADS, "0");
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
