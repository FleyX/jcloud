package com.fleyx.jcloud.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.mapper.SystemConfigMapper;
import com.fleyx.jcloud.model.po.SystemConfig;
import com.fleyx.jcloud.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 系统配置业务实现。
 */
@Service
@RequiredArgsConstructor
public class SystemConfigServiceImpl implements SystemConfigService {

    private final SystemConfigMapper systemConfigMapper;

    @Override
    public String getValue(String key, String defaultValue) {
        if (StrUtil.isBlank(key)) {
            return defaultValue;
        }
        LambdaQueryWrapper<SystemConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SystemConfig::getConfigKey, key);
        SystemConfig config = systemConfigMapper.selectOne(wrapper);
        return config == null || config.getConfigValue() == null ? defaultValue : config.getConfigValue();
    }

    @Override
    public void setValue(String key, String value) {
        if (StrUtil.isBlank(key)) {
            return;
        }
        LambdaQueryWrapper<SystemConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SystemConfig::getConfigKey, key);
        SystemConfig existing = systemConfigMapper.selectOne(wrapper);
        if (existing != null) {
            existing.setConfigValue(value);
            systemConfigMapper.updateById(existing);
            return;
        }
        SystemConfig config = new SystemConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        systemConfigMapper.insert(config);
    }
}
