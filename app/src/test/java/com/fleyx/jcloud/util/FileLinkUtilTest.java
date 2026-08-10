package com.fleyx.jcloud.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件复用工具测试。
 */
class FileLinkUtilTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCopyWhenHardLinkFails() throws IOException {
        Path source = tempDir.resolve("source.txt");
        Path target = tempDir.resolve("target.txt");
        Files.writeString(source, "hello");

        FileLinkUtil.linkOrCopy(source, target, (s, t) -> {
            throw new IOException("cross-device link not allowed");
        });

        assertTrue(Files.exists(target));
        assertEquals("hello", Files.readString(target));
    }

    @Test
    void shouldUseHardLinkWhenAvailable() throws IOException {
        Path source = tempDir.resolve("source.txt");
        Path target = tempDir.resolve("target.txt");
        Files.writeString(source, "hello");

        FileLinkUtil.linkOrCopy(source, target);

        assertTrue(Files.exists(target));
        assertEquals("hello", Files.readString(target));
        // 硬链接语义：源与目标指向同一文件（而非内容相同但物理独立的副本）
        assertTrue(Files.isSameFile(source, target));
    }
}
