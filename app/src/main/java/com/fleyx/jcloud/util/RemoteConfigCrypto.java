package com.fleyx.jcloud.util;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.crypto.symmetric.AES;
import com.fleyx.jcloud.config.RemoteProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 远程挂载配置敏感字段加密工具。
 * <p>
 * 使用 AES-256 对密码、accessKey 等字段进行加密存储；解密密钥来自配置。
 */
@Component
public class RemoteConfigCrypto {

    private final AES aes;

    public RemoteConfigCrypto(RemoteProperties remoteProperties) {
        String secret = remoteProperties.getEncryptionKey();
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("jcloud.remote.encryption-key 未配置");
        }
        byte[] keyBytes = DigestUtil.sha256(secret.getBytes(StandardCharsets.UTF_8));
        this.aes = SecureUtil.aes(keyBytes);
    }

    /**
     * 加密明文。
     *
     * @param plain 明文
     * @return 密文 Base64
     */
    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        return aes.encryptBase64(plain);
    }

    /**
     * 解密密文。
     *
     * @param cipher 密文 Base64
     * @return 明文
     */
    public String decrypt(String cipher) {
        if (cipher == null) {
            return null;
        }
        return aes.decryptStr(cipher);
    }
}
