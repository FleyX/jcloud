package com.fleyx.jcloud.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContentDispositionUtil 测试。
 * <p>
 * 验证关键点：header 值只包含 ASCII 字符（否则 Tomcat 会丢弃整个 header），
 * 中文文件名走 RFC 5987（filename*=UTF-8''）百分号编码。
 */
class ContentDispositionUtilTest {

    @Test
    void asciiFilenameKeepsQuotedFormAndRfc5987Form() {
        String value = ContentDispositionUtil.attachment("ascii-test.txt");
        assertEquals("attachment; filename=\"ascii-test.txt\"; filename*=UTF-8''ascii-test.txt", value);
        assertTrue(value.startsWith("attachment; "));
    }

    @Test
    void chineseFilenameUsesRfc5987PercentEncoding() {
        String value = ContentDispositionUtil.attachment("中文文件名.txt");
        assertTrue(value.contains("; filename*=UTF-8''%E4%B8%AD%E6%96%87%E6%96%87%E4%BB%B6%E5%90%8D.txt"),
                "actual=" + value);
        assertEquals("中文文件名.txt", percentDecode(value.substring(value.indexOf("filename*=UTF-8''") + "filename*=UTF-8''".length())));
    }

    @Test
    void chineseFilenameHeaderIsAsciiOnly() {
        // bug 根因回归：header 含非 ISO-8859-1 字符会被 Tomcat 静默丢弃
        String value = ContentDispositionUtil.attachment("中文文件名.txt");
        assertTrue(StandardCharsets.ISO_8859_1.newEncoder().canEncode(value), "header 必须只含 ISO-8859-1 字符");
        assertTrue(value.chars().allMatch(c -> c <= 0xFF), "actual=" + value);
    }

    @Test
    void filenameWithSpaceAndQuoteEncodedPerRfc5987() {
        String value = ContentDispositionUtil.attachment("with space and \"quote\".txt");
        assertEquals("attachment; filename=\"with space and \\\"quote\\\".txt\"; filename*=UTF-8''with%20space%20and%20%22quote%22.txt", value);
    }

    @Test
    void filenameWithBackslashEscapedInQuotedForm() {
        String value = ContentDispositionUtil.attachment("back\\slash.txt");
        assertEquals("attachment; filename=\"back\\\\slash.txt\"; filename*=UTF-8''back%5Cslash.txt", value);
    }

    @Test
    void inlineTypeIsPreserved() {
        String value = ContentDispositionUtil.inline("cover.jpg");
        assertEquals("inline; filename=\"cover.jpg\"; filename*=UTF-8''cover.jpg", value);
    }

    /** 只解码 %XX 序列（不处理 '+'，避免与 RFC 5987 中允许的 '+' 字面量冲突）。 */
    private static String percentDecode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            if (b == '%' && i + 2 < bytes.length) {
                int hi = Character.digit((char) bytes[i + 1], 16);
                int lo = Character.digit((char) bytes[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            out.write(b);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
