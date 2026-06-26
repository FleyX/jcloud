package com.fleyx.jcloud.service;

/**
 * 系统配置业务接口。
 */
public interface SystemConfigService {

    /**
     * 根据键获取配置值。
     *
     * @param key          配置键
     * @param defaultValue 不存在时返回的默认值
     * @return 配置值，不存在时返回 defaultValue
     */
    String getValue(String key, String defaultValue);

    /**
     * 设置配置值，不存在则新增，存在则更新。
     *
     * @param key   配置键
     * @param value 配置值
     */
    void setValue(String key, String value);
}
