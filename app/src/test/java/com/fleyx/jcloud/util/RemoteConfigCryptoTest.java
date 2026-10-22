package com.fleyx.jcloud.util;

import com.fleyx.jcloud.config.SystemProperties;
import com.fleyx.jcloud.service.support.SystemSecretSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 远程挂载配置加密工具测试。
 */
class RemoteConfigCryptoTest {

    @TempDir
    Path secretDir;

    private RemoteConfigCrypto buildCrypto() {
        SystemProperties systemProperties = new SystemProperties();
        systemProperties.setSecretDir(secretDir.toString());
        return new RemoteConfigCrypto(new SystemSecretSupport(systemProperties));
    }

    @Test
    void shouldEncryptAndDecrypt() {
        RemoteConfigCrypto crypto = buildCrypto();

        String plain = "my-secret-password";
        String cipher = crypto.encrypt(plain);

        assertNotEquals(plain, cipher);
        assertEquals(plain, crypto.decrypt(cipher));
    }

    @Test
    void shouldReturnNullForNullInput() {
        RemoteConfigCrypto crypto = buildCrypto();

        assertNull(crypto.encrypt(null));
        assertNull(crypto.decrypt(null));
    }
}
