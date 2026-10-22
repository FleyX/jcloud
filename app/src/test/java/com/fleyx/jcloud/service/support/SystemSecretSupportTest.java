package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.config.SystemProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统密钥提供者测试：首次生成落盘、新实例复用、不同用途密钥相互隔离。
 */
class SystemSecretSupportTest {

    @TempDir
    Path secretDir;

    private SystemSecretSupport supportOf(Path dir) {
        SystemProperties properties = new SystemProperties();
        properties.setSecretDir(dir.toString());
        return new SystemSecretSupport(properties);
    }

    @Test
    void shouldGenerateAndPersistSecretOnFirstCall() throws Exception {
        SystemSecretSupport support = supportOf(secretDir);

        String secret = support.getTokenSecret();

        assertFalse(secret.isBlank());
        Path secretFile = secretDir.resolve("token-secret");
        assertTrue(Files.isRegularFile(secretFile));
        assertEquals(secret, Files.readString(secretFile, StandardCharsets.UTF_8).trim());
    }

    @Test
    void shouldReuseSecretAcrossInstances() {
        String first = supportOf(secretDir).getTokenSecret();

        String second = supportOf(secretDir).getTokenSecret();

        assertEquals(first, second);
    }

    @Test
    void shouldUseDifferentSecretsForDifferentPurposes() {
        SystemSecretSupport support = supportOf(secretDir);

        assertNotEquals(support.getTokenSecret(), support.getRemoteEncryptionKey());
    }
}
