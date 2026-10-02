package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.config.SystemCacheProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统缓存目录提供者测试：直接返回配置路径并创建目录，创建失败包装为系统异常。
 */
class SystemCacheDirProviderTest {

    @TempDir
    Path tempDir;

    private SystemCacheDirProvider providerOf(String cacheDir) {
        SystemCacheProperties properties = new SystemCacheProperties();
        properties.setCacheDir(cacheDir);
        return new SystemCacheDirProvider(properties);
    }

    @Test
    void shouldReturnConfiguredCacheDirAndCreateIt() {
        Path cacheDir = tempDir.resolve("system-cache");

        SystemCacheDirProvider provider = providerOf(cacheDir.toString());
        provider.init();

        assertEquals(cacheDir, provider.getCacheDir());
        assertTrue(Files.isDirectory(cacheDir));
    }

    @Test
    void shouldWrapCreateFailureAsSystemException() throws Exception {
        // 缓存路径的父级是普通文件，createDirectories 必然失败
        Path blockingFile = tempDir.resolve("blocking-file");
        Files.writeString(blockingFile, "x");
        SystemCacheDirProvider provider = providerOf(blockingFile.resolve("nested").toString());

        assertThrows(SystemException.class, provider::init);
    }
}
