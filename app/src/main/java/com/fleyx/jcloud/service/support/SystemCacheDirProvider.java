package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.config.SystemCacheProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 系统缓存目录提供者：直接返回配置 {@code jcloud.system.cache-dir} 指定的缓存根目录，
 * 不查询系统配置、不绑定用户存储空间。预览、ZIP 打包、字幕缓存、转码会话均以此为根。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemCacheDirProvider {

    private final SystemCacheProperties systemCacheProperties;

    /**
     * 启动时创建缓存根目录，避免首次写入时目录缺失。
     */
    @PostConstruct
    void init() {
        Path cacheDir = getCacheDir();
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "系统缓存目录创建失败: " + cacheDir, e);
        }
        log.info("系统缓存目录: {}", cacheDir.toAbsolutePath());
    }

    /**
     * 系统缓存根目录。
     */
    public Path getCacheDir() {
        return Path.of(systemCacheProperties.getCacheDir());
    }
}
