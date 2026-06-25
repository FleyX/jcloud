package com.fleyx.jcloud.util;

import cn.hutool.core.util.HexUtil;
import cn.hutool.crypto.digest.DigestUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 文件 hash 计算工具。
 * <p>
 * 文件身份 hash 策略：
 * <ul>
 *   <li>文件小于 150MB：计算完整文件 MD5。</li>
 *   <li>文件大于等于 150MB：取前 50MB、中间 50MB（从 size/2 开始）、后 50MB 拼接后计算 MD5。</li>
 * </ul>
 */
public final class FileHashUtil {

    private static final long SAMPLE_SIZE = 50L * 1024 * 1024;
    private static final long THRESHOLD = 150L * 1024 * 1024;

    private FileHashUtil() {
    }

    /**
     * 计算文件身份 hash。
     *
     * @param path 文件路径
     * @return 身份 hash
     * @throws IOException 读取失败
     */
    public static String identityHash(Path path) throws IOException {
        long size = java.nio.file.Files.size(path);
        if (size < THRESHOLD) {
            return fullHash(path);
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            feed(channel, digest, 0, SAMPLE_SIZE, buffer);
            feed(channel, digest, size / 2, SAMPLE_SIZE, buffer);
            feed(channel, digest, size - SAMPLE_SIZE, SAMPLE_SIZE, buffer);
            return HexUtil.encodeHexStr(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 算法不可用", e);
        }
    }

    /**
     * 计算文件完整 MD5 hash。
     *
     * @param path 文件路径
     * @return 完整 hash
     * @throws IOException 读取失败
     */
    public static String fullHash(Path path) throws IOException {
        return DigestUtil.md5Hex(java.nio.file.Files.newInputStream(path));
    }

    private static void feed(FileChannel channel, MessageDigest digest, long position,
                             long amount, ByteBuffer buffer) throws IOException {
        long remaining = amount;
        while (remaining > 0) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), remaining));
            int read = channel.read(buffer, position);
            if (read == -1) {
                break;
            }
            buffer.flip();
            digest.update(buffer);
            position += read;
            remaining -= read;
        }
    }
}
