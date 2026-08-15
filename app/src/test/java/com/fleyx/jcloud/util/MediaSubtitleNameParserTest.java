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

    @Test
    void testIsBitmapSubtitleFile() {
        assertTrue(MediaSubtitleNameParser.isBitmapSubtitleFile("Movie.sup"));
        assertTrue(MediaSubtitleNameParser.isBitmapSubtitleFile("Movie.SUP"));
        assertTrue(MediaSubtitleNameParser.isBitmapSubtitleFile("Movie.idx"));
        // .sub 与文本 MicroDVD 格式同名冲突，不识别为位图字幕文件
        assertFalse(MediaSubtitleNameParser.isBitmapSubtitleFile("Movie.sub"));
        assertFalse(MediaSubtitleNameParser.isBitmapSubtitleFile("Movie.srt"));
        assertFalse(MediaSubtitleNameParser.isBitmapSubtitleFile("noext"));
        assertFalse(MediaSubtitleNameParser.isBitmapSubtitleFile(null));
    }

    @Test
    void testIsBitmapFormat() {
        assertTrue(MediaSubtitleNameParser.isBitmapFormat("sup"));
        assertTrue(MediaSubtitleNameParser.isBitmapFormat("idx"));
        assertFalse(MediaSubtitleNameParser.isBitmapFormat("srt"));
        assertFalse(MediaSubtitleNameParser.isBitmapFormat("sub"));
        assertFalse(MediaSubtitleNameParser.isBitmapFormat(null));
    }

    @Test
    void testParseBitmapSubtitleFiles() {
        // 位图单文件 .sup：语言段解析与文本外挂一致
        MediaSubtitleNameParser.SubtitleNameMatch sup =
                MediaSubtitleNameParser.parse("Movie", "Movie.zh.sup");
        assertEquals("sup", sup.format());
        assertEquals("简体", sup.label());
        assertFalse(sup.defaulted());
        // .idx：语言段与 .default 约定同样生效
        MediaSubtitleNameParser.SubtitleNameMatch idx =
                MediaSubtitleNameParser.parse("Movie", "Movie.zh.default.idx");
        assertEquals("idx", idx.format());
        assertEquals("简体", idx.label());
        assertTrue(idx.defaulted());
        // .sub 统一不识别（不产生关联），避免 MicroDVD 文本格式同名嗅探
        assertNull(MediaSubtitleNameParser.parse("Movie", "Movie.zh.sub"));
        // 前缀匹配规则对位图同样生效
        assertNull(MediaSubtitleNameParser.parse("Movie", "Movie2.sup"));
    }
}
