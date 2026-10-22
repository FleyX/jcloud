package com.fleyx.jcloud.service.support;

import cn.hutool.core.util.HexUtil;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.config.SystemProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统密钥提供者：从 {@code jcloud.system.secret-dir} 读取密钥文件，不存在时生成后落盘。
 * <p>
 * 取消环境变量覆盖入口，避免部署时沿用公开默认密钥或人为配置弱密钥；密钥只在首次启动时生成，
 * 之后随密钥目录持久化复用。token 签名与远程凭据加密使用各自独立的密钥文件，
 * 任一用途泄漏都不会波及另一用途。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemSecretSupport {

    /** token 签名密钥文件名。 */
    private static final String TOKEN_SECRET_FILE = "token-secret";

    /** 远程挂载凭据加密密钥文件名。 */
    private static final String REMOTE_ENCRYPTION_KEY_FILE = "remote-encryption-key";

    /** 生成密钥的字节数：32 字节转 hex 后为 64 字符，满足 HMAC-SHA256 与 AES-256 的强度要求。 */
    private static final int SECRET_BYTES = 32;

    private static final String DIR_PERMISSIONS = "rwx------";
    private static final String FILE_PERMISSIONS = "rw-------";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SystemProperties systemProperties;

    /**
     * 进程内缓存：密钥在进程生命周期内不会变化，避免每次签名/加解密都读盘。
     */
    private final Map<String, String> secrets = new ConcurrentHashMap<>();

    /**
     * token 签名密钥（JWT 与分享令牌共用）。
     */
    public String getTokenSecret() {
        return loadOrGenerate(TOKEN_SECRET_FILE);
    }

    /**
     * 远程挂载凭据加密密钥。
     */
    public String getRemoteEncryptionKey() {
        return loadOrGenerate(REMOTE_ENCRYPTION_KEY_FILE);
    }

    private String loadOrGenerate(String fileName) {
        return secrets.computeIfAbsent(fileName, this::readOrCreate);
    }

    private String readOrCreate(String fileName) {
        Path secretDir = Path.of(systemProperties.getSecretDir());
        Path secretFile = secretDir.resolve(fileName);
        try {
            if (Files.isRegularFile(secretFile)) {
                String existing = Files.readString(secretFile, StandardCharsets.UTF_8).trim();
                // 空文件视为未初始化，重新生成，避免用空密钥签名
                if (!existing.isEmpty()) {
                    return existing;
                }
            }
            String generated = generateSecret();
            Files.createDirectories(secretDir);
            restrictPermissions(secretDir, DIR_PERMISSIONS);
            Files.writeString(secretFile, generated, StandardCharsets.UTF_8);
            restrictPermissions(secretFile, FILE_PERMISSIONS);
            log.info("已生成系统密钥文件: {}", secretFile.toAbsolutePath());
            return generated;
        } catch (IOException e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "系统密钥读取/写入失败: " + secretFile, e);
        }
    }

    private static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return HexUtil.encodeHexStr(bytes);
    }

    /**
     * 尽力收紧密钥文件/目录权限；非 POSIX 文件系统不支持时降级为系统默认权限，不影响功能。
     */
    private static void restrictPermissions(Path path, String permissions) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("无法设置 POSIX 权限，使用默认权限: {}", path, e);
        }
    }
}
