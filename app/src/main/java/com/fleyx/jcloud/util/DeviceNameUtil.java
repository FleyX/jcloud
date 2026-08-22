package com.fleyx.jcloud.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.useragent.UserAgent;
import cn.hutool.http.useragent.UserAgentUtil;

/**
 * 设备名解析工具。
 */
public final class DeviceNameUtil {

    private DeviceNameUtil() {
    }

    /**
     * 解析设备名：显式自报优先，否则从 User-Agent 解析为“浏览器 · 操作系统”，失败返回“未知设备”。
     *
     * @param explicitDeviceName 显式设备名
     * @param userAgent          User-Agent
     * @return 设备名
     */
    public static String resolveDeviceName(String explicitDeviceName, String userAgent) {
        if (StrUtil.isNotBlank(explicitDeviceName)) {
            return explicitDeviceName;
        }
        if (StrUtil.isBlank(userAgent)) {
            return "未知设备";
        }
        try {
            UserAgent ua = UserAgentUtil.parse(userAgent);
            if (ua == null) {
                return "未知设备";
            }
            String browser = ua.getBrowser() != null ? ua.getBrowser().getName() : null;
            String os = ua.getOs() != null ? ua.getOs().getName() : null;
            boolean browserKnown = StrUtil.isNotBlank(browser) && !"Unknown".equalsIgnoreCase(browser);
            boolean osKnown = StrUtil.isNotBlank(os) && !"Unknown".equalsIgnoreCase(os);
            if (browserKnown && osKnown) {
                return browser + " · " + os;
            }
            if (browserKnown) {
                return browser;
            }
            if (osKnown) {
                return os;
            }
            return "未知设备";
        } catch (Exception e) {
            return "未知设备";
        }
    }
}
