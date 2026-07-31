package com.fleyx.jcloud.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字幕编码处理工具单元测试。
 */
class SubtitleCharsetUtilTest {

    @TempDir
    Path tempDir;

    @Test
    void testIsUtf8() {
        assertTrue(SubtitleCharsetUtil.isUtf8("plain ascii".getBytes(StandardCharsets.UTF_8)));
        assertTrue(SubtitleCharsetUtil.isUtf8("中文字幕".getBytes(StandardCharsets.UTF_8)));
        assertFalse(SubtitleCharsetUtil.isUtf8("中文字幕".getBytes(Charset.forName("GBK"))));
    }

    @Test
    void testUtf8FileReturnedAsIs() throws Exception {
        Path input = tempDir.resolve("utf8.srt");
        Files.writeString(input, "1\n00:00:01,000 --> 00:00:02,000\n你好\n", StandardCharsets.UTF_8);

        Path result = SubtitleCharsetUtil.ensureUtf8(input);

        assertEquals(input, result);
    }

    @Test
    void testGbkFileConvertedToUtf8Temp() throws Exception {
        String text = "1\n00:00:01,000 --> 00:00:02,000\n你好，世界\n";
        Path input = tempDir.resolve("gbk.srt");
        Files.write(input, text.getBytes(Charset.forName("GBK")));

        Path result = SubtitleCharsetUtil.ensureUtf8(input);
        try {
            assertNotEquals(input, result);
            assertTrue(Files.exists(result));
            assertEquals(text, Files.readString(result, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(result);
        }
    }
}
