package com.fleyx.jcloud.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体文件名解析工具单元测试。
 */
class MediaFileNameParserTest {

    @Test
    void testIsVideoFile() {
        assertTrue(MediaFileNameParser.isVideoFile("movie.mkv"));
        assertTrue(MediaFileNameParser.isVideoFile("movie.MP4"));
        assertFalse(MediaFileNameParser.isVideoFile("movie.srt"));
        assertFalse(MediaFileNameParser.isVideoFile("noext"));
        assertFalse(MediaFileNameParser.isVideoFile(null));
    }

    @Test
    void testParseEpisodeWithSeason() {
        MediaFileNameParser.ParseResult result = MediaFileNameParser.parse(
                "The.Last.of.Us.S02E05.1080p.WEB-DL.mkv", null, null);
        assertEquals(2, result.seasonNo());
        assertEquals(5, result.episodeNo());
        assertEquals("The Last of Us", result.title());
    }

    @Test
    void testParseEpisodeWithoutSeasonDefaultsToOne() {
        MediaFileNameParser.ParseResult result = MediaFileNameParser.parse(
                "葬送的芙莉莲.E12.mkv", null, null);
        assertEquals(1, result.seasonNo());
        assertEquals(12, result.episodeNo());
        assertEquals("葬送的芙莉莲", result.title());
    }

    @Test
    void testParseEpisodeChinese() {
        MediaFileNameParser.ParseResult result = MediaFileNameParser.parse(
                "某某剧 第08集.mkv", null, null);
        assertEquals(8, result.episodeNo());
    }

    @Test
    void testSeasonFallbackFromParentDir() {
        MediaFileNameParser.ParseResult result = MediaFileNameParser.parse(
                "Ep03.mkv", "Season 2", null);
        assertEquals(2, result.seasonNo());
        assertEquals(3, result.episodeNo());
    }

    @Test
    void testSeasonFallbackFromChineseParentDir() {
        MediaFileNameParser.ParseResult result = MediaFileNameParser.parse(
                "第5集.mkv", "第三季", null);
        assertEquals(3, result.seasonNo());
        assertEquals(5, result.episodeNo());
    }

    @Test
    void testSeriesTitleFallbackFromGrandParentDir() {
        MediaFileNameParser.ParseResult result = MediaFileNameParser.parse(
                "S01E01.mkv", "Season 1", "权力的游戏");
        assertEquals("权力的游戏", result.title());
    }

    @Test
    void testParseMovieWithYear() {
        MediaFileNameParser.ParseResult result = MediaFileNameParser.parse(
                "Inception.2010.1080p.BluRay.x264.mkv", null, null);
        assertEquals(2010, result.year());
        assertNull(result.episodeNo());
        assertEquals("Inception", result.title());
    }

    @Test
    void testParseMovieWithBrackets() {
        MediaFileNameParser.ParseResult result = MediaFileNameParser.parse(
                "流浪地球2 (2023) [1080p].mp4", null, null);
        assertEquals(2023, result.year());
        assertEquals("流浪地球2", result.title());
    }

    @Test
    void testCleanTitle() {
        assertEquals("Breaking Bad", MediaFileNameParser.cleanTitle("Breaking.Bad.2008.1080p.BluRay"));
        assertEquals("某某剧", MediaFileNameParser.cleanTitle("某某剧【蓝光】"));
    }

    @Test
    void testCleanTitleStripsSeasonWord() {
        assertEquals("庆余年", MediaFileNameParser.cleanTitle("庆余年 第二季"));
        assertEquals("权力的游戏", MediaFileNameParser.cleanTitle("权力的游戏 Season 2"));
    }

    @Test
    void testCleanTitleStripsReleaseGroup() {
        assertEquals("The Last of Us", MediaFileNameParser.cleanTitle("The.Last.of.Us.2023.1080p.WEB-DL-GRP"));
    }

    @Test
    void testCleanTitleStripsVersionTags() {
        assertEquals("某电影", MediaFileNameParser.cleanTitle("某电影 导演剪辑版"));
        assertEquals("某剧", MediaFileNameParser.cleanTitle("某剧 中文字幕 全集"));
    }
}
