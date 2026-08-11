package com.fleyx.jcloud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件相关配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jcloud.file")
public class FileProperties {

    /**
     * 文件夹大小防抖窗口（秒）：距最后一条文件树变更事件无新事件时才批量重算受影响祖先链大小。
     */
    private int folderSizeDebounceSeconds = 10;
}
