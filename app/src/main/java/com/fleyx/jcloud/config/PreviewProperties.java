package com.fleyx.jcloud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件预览相关配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jcloud.preview")
public class PreviewProperties {

    /**
     * LibreOffice soffice 可执行文件路径，默认从 PATH 查找。
     */
    private String libreofficePath = "soffice";

    /**
     * Office 文档在线转换的大小上限（字节），默认 50MB。
     * 超过该大小的 Office 文件不做在线转换，提示用户下载查看。
     * PDF 原文件不受此限制。
     */
    private long officeMaxConvertSize = 50L * 1024 * 1024;

    /**
     * 单次 LibreOffice 转换超时时间（秒），默认 60 秒。
     */
    private long convertTimeoutSeconds = 60;

    /**
     * 全局最大并发转换数，默认 2。
     */
    private int maxConcurrentConversions = 2;
}
