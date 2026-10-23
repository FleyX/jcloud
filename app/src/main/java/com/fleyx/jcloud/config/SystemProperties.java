package com.fleyx.jcloud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 系统级目录配置（缓存目录 + 密钥目录）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jcloud.system")
public class SystemProperties {

    /**
     * 系统缓存根目录：存放预览、字幕缓存、转码切片、ZIP 临时包等派生数据。
     */
    private String cacheDir = "./data/system-cache";

    /**
     * 系统密钥目录：存放 token 签名、远程挂载凭据加密等不可再生成的密钥。
     * 与 cacheDir 语义相反——缓存可整体清空，密钥目录一旦丢失，已签发的 token 全部失效、
     * 已加密的远程凭据无法解密，因此必须纳入持久化卷。
     */
    private String secretDir = "./data/system";
}
