package com.fleyx.jcloud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 系统缓存目录配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jcloud.system")
public class SystemCacheProperties {

    /**
     * 系统缓存根目录：存放预览、字幕缓存、转码切片、ZIP 临时包等派生数据。
     */
    private String cacheDir = "./data/system-cache";
}
