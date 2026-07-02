package com.fleyx.jcloud.util;

import com.fleyx.jcloud.config.RemoteProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 远程挂载配置加密工具测试。
 */
class RemoteConfigCryptoTest {

    @Test
    void shouldEncryptAndDecrypt() {
        RemoteProperties properties = new RemoteProperties();
        properties.setEncryptionKey("test-encryption-key");
        RemoteConfigCrypto crypto = new RemoteConfigCrypto(properties);

        String plain = "my-secret-password";
        String cipher = crypto.encrypt(plain);

        assertNotEquals(plain, cipher);
        assertEquals(plain, crypto.decrypt(cipher));
    }

    @Test
    void shouldReturnNullForNullInput() {
        RemoteProperties properties = new RemoteProperties();
        properties.setEncryptionKey("test-encryption-key");
        RemoteConfigCrypto crypto = new RemoteConfigCrypto(properties);

        assertEquals(null, crypto.encrypt(null));
        assertEquals(null, crypto.decrypt(null));
    }

    @Test
    void shouldRejectEmptyEncryptionKey() {
        RemoteProperties properties = new RemoteProperties();
        assertThrows(IllegalArgumentException.class, () -> new RemoteConfigCrypto(properties));
    }
}
