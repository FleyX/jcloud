package com.fleyx.jcloud.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部字幕文件名解析工具单元测试。
 */
class MediaSubtitleNameParserTest {

    @Test
    void testIsSubtitleFileCaseInsensitive() {
        assertTrue(MediaSubtitleNameParser.isSubtitleFile("Movie.srt"));
        assertTrue(MediaSubtitleNameParser.isSubtitleFile("Movie.SRT"));
        assertTrue(MediaSubtitleNameParser.isSubtitleFile("Movie.ass"));
        assertTrue(MediaSubtitleNameParser.isSubtitleFile("Movie.SSA"));
        assertTrue(MediaSubtitleNameParser.isSubtitleFile("Movie.vtt"));
        assertFalse(MediaSubtitleNameParser.isSubtitleFile("Movie.txt"));
        assertFalse(MediaSubtitleNameParser.isSubtitleFile("Movie.mkv"));
        assertFalse(MediaSubtitleNameParser.isSubtitleFile("noext"));
        assertFalse(MediaSubtitleNameParser.isSubtitleFile(null));
    }

    @Test
    void testExactMainNameMatch() {
        MediaSubtitleNameParser.SubtitleNameMatch match = MediaSubtitleNameParser.parse("Movie", "Movie.srt");
        assertEquals("srt", match.format());
        assertNull(match.label());
        assertFalse(match.defaulted());
    }

    @Test
    void testPrefixMustFollowDot() {
        // Movie.mkv 不能误配 Movie2.srt / Movie2.zh.srt
        assertNull(MediaSubtitleNameParser.parse("Movie", "Movie2.srt"));
        assertNull(MediaSubtitleNameParser.parse("Movie", "Movie2.zh.srt"));
        assertNull(MediaSubtitleNameParser.parse("Movie", "Movie-2.srt"));
        // 主文件名不同的字幕不匹配
        assertNull(MediaSubtitleNameParser.parse("Movie", "Other.zh.srt"));
        // 非字幕文件不匹配
        assertNull(MediaSubtitleNameParser.parse("Movie", "Movie.nfo"));
    }

    @Test
    void testMainNameContainingDots() {
        MediaSubtitleNameParser.SubtitleNameMatch match =
                MediaSubtitleNameParser.parse("Movie.2024", "Movie.2024.zh.srt");
        assertEquals("简体", match.label());
        assertFalse(match.defaulted());
        // 扩展名判断基于最后一段，主名含点不受影响
        assertNull(MediaSubtitleNameParser.parse("Movie.2024", "Movie.2024x.zh.srt"));
    }

    @Test
    void testDefaultSuffixCaseInsensitive() {
        MediaSubtitleNameParser.SubtitleNameMatch match =
                MediaSubtitleNameParser.parse("Movie", "Movie.zh.default.srt");
        assertTrue(match.defaulted());
        assertEquals("简体", match.label());

        MediaSubtitleNameParser.SubtitleNameMatch upper =
                MediaSubtitleNameParser.parse("Movie", "Movie.DEFAULT.ass");
        assertTrue(upper.defaulted());
        assertNull(upper.label());
    }

    @Test
    void testLanguageLabelMapping() {
        assertEquals("简体", MediaSubtitleNameParser.parse("Movie", "Movie.chs.srt").label());
        assertEquals("简体", MediaSubtitleNameParser.parse("Movie", "Movie.zh-cn.srt").label());
        assertEquals("繁體", MediaSubtitleNameParser.parse("Movie", "Movie.cht.srt").label());
        assertEquals("English", MediaSubtitleNameParser.parse("Movie", "Movie.eng.srt").label());
        assertEquals("日语", MediaSubtitleNameParser.parse("Movie", "Movie.jpn.srt").label());
        // 映射不到保留原段文本
        assertEquals("导演剪辑", MediaSubtitleNameParser.parse("Movie", "Movie.导演剪辑.srt").label());
    }

    @Test
    void testMultiSegmentLabel() {
        MediaSubtitleNameParser.SubtitleNameMatch match =
                MediaSubtitleNameParser.parse("Movie", "Movie.chs.特效.default.ass");
        assertTrue(match.defaulted());
        assertEquals("简体.特效", match.label());
        assertEquals("ass", match.format());
    }

    @Test
    void testBlankInputRejected() {
        assertNull(MediaSubtitleNameParser.parse(null, "Movie.srt"));
        assertNull(MediaSubtitleNameParser.parse("", "Movie.srt"));
        assertNull(MediaSubtitleNameParser.parse("Movie", null));
    }
}
