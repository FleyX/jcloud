package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.NotificationEventType;
import com.fleyx.jcloud.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 通知事件开关支撑类：集中持有开关配置键与读写逻辑。
 * <p>
 * 键为 {@code notification.event.{eventType}.enabled}，无记录视为启用；禁用后该事件不触发任何渠道。
 */
@Component
@RequiredArgsConstructor
public class NotificationSwitchSupport {

    /**
     * 系统配置键前缀。
     */
    public static final String CONFIG_KEY_PREFIX = "notification.event.";

    /**
     * 系统配置键后缀。
     */
    public static final String CONFIG_KEY_SUFFIX = ".enabled";

    private final SystemConfigService systemConfigService;

    /**
     * 判断事件是否启用，无配置记录视为启用。
     *
     * @param eventType 事件类型
     * @return true 启用
     */
    public boolean isEnabled(NotificationEventType eventType) {
        return Boolean.parseBoolean(systemConfigService.getValue(configKey(eventType), Boolean.TRUE.toString()));
    }

    /**
     * 加载全部事件开关状态，顺序与枚举定义一致。
     *
     * @return 事件类型到启用状态的映射
     */
    public Map<NotificationEventType, Boolean> loadAll() {
        Map<NotificationEventType, Boolean> switches = new EnumMap<>(NotificationEventType.class);
        for (NotificationEventType eventType : NotificationEventType.values()) {
            switches.put(eventType, isEnabled(eventType));
        }
        return switches;
    }

    /**
     * 设置事件开关。
     *
     * @param eventType 事件类型
     * @param enabled   是否启用
     */
    public void set(NotificationEventType eventType, boolean enabled) {
        systemConfigService.setValue(configKey(eventType), Boolean.toString(enabled));
    }

    /**
     * 事件开关对应的系统配置键。
     *
     * @param eventType 事件类型
     * @return 配置键
     */
    public static String configKey(NotificationEventType eventType) {
        return CONFIG_KEY_PREFIX + eventType.getValue() + CONFIG_KEY_SUFFIX;
    }
}
