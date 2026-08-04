package com.fleyx.jcloud.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebVTT cue 时间偏移单元测试（纯逻辑，不依赖 Spring/ffmpeg）。
 */
class WebVttOffsetUtilTest {

    @Test
    void shouldReturnInputWhenNoOffset() {
        String vtt = "WEBVTT\n\n00:01.000 --> 00:04.000\nhello\n";
        assertEquals(vtt, WebVttOffsetUtil.applyOffset(vtt, 0));
        assertEquals(vtt, WebVttOffsetUtil.applyOffset(vtt, -5));
    }

    @Test
    void shouldShiftCuesByOffset() {
        String vtt = """
                WEBVTT

                00:01.000 --> 00:04.000
                hello

                00:05.000 --> 00:06.000
                world
                """;
        String adjusted = WebVttOffsetUtil.applyOffset(vtt, 2000);
        assertTrue(adjusted.contains("00:00.000 --> 00:02.000"));
        assertTrue(adjusted.contains("00:03.000 --> 00:04.000"));
        assertTrue(adjusted.contains("hello"));
        assertTrue(adjusted.contains("world"));
    }

    @Test
    void shouldHandleHoursFormatAndCueSettings() {
        String vtt = """
                WEBVTT

                00:01:02.500 --> 00:01:05.000 align:start position:10%
                line1
                line2
                """;
        String adjusted = WebVttOffsetUtil.applyOffset(vtt, 2000);
        assertTrue(adjusted.contains("00:01:00.500 --> 00:01:03.000 align:start position:10%"));
        assertTrue(adjusted.contains("line1"));
        assertTrue(adjusted.contains("line2"));
    }

    @Test
    void shouldClampCrossingCueStartToZero() {
        String vtt = "WEBVTT\n\n00:01.000 --> 00:04.000\n你好\n";
        String adjusted = WebVttOffsetUtil.applyOffset(vtt, 2000);
        assertTrue(adjusted.contains("00:00.000 --> 00:02.000"));
        assertTrue(adjusted.contains("你好"));
    }

    @Test
    void shouldDropFullyExpiredCues() {
        String vtt = """
                WEBVTT

                00:01.000 --> 00:03.000
                old

                00:05.000 --> 00:06.000
                new
                """;
        String adjusted = WebVttOffsetUtil.applyOffset(vtt, 4500);
        assertFalse(adjusted.contains("old"));
        assertTrue(adjusted.contains("00:00.500 --> 00:01.500"));
        assertTrue(adjusted.contains("new"));
    }

    @Test
    void shouldPreserveNoteBlocksAndCueIdentifiers() {
        String vtt = """
                WEBVTT

                NOTE this is a comment

                1
                00:01.000 --> 00:04.000
                hello
                """;
        String adjusted = WebVttOffsetUtil.applyOffset(vtt, 1000);
        assertTrue(adjusted.contains("NOTE this is a comment"));
        assertTrue(adjusted.contains("1\n00:00.000 --> 00:03.000"));
        assertTrue(adjusted.contains("hello"));
    }
}
