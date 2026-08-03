package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.model.po.MediaMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 媒体 NFO 支撑组件测试（issue #21 起仅覆盖解析/命名/新模型生成路径，本地元数据 upsert 已随旧表弃用删除）。
 */
class MediaNfoSupportTest {

    private final MediaNfoSupport nfoSupport = new MediaNfoSupport();

    /**
     * 电影 NFO 完整字段解析。
     */
    @Test
    void shouldParseMovieNfo() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <movie>
                  <tmdbid>27205</tmdbid>
                  <title>盗梦空间</title>
                  <originaltitle>Inception</originaltitle>
                  <plot>造梦师盗取机密</plot>
                  <year>2010</year>
                  <premiered>2010-07-16</premiered>
                  <rating>8.8</rating>
                  <genre>科幻</genre>
                  <genre>悬疑</genre>
                </movie>
                """;
        MediaNfoSupport.NfoData data = nfoSupport.parse(xml);

        assertEquals("movie", data.mediaType());
        assertEquals(27205L, data.tmdbId());
        assertEquals("盗梦空间", data.title());
        assertEquals("Inception", data.originalTitle());
        assertEquals("造梦师盗取机密", data.overview());
        assertEquals("2010-07-16", data.releaseDate());
        assertEquals(8.8, data.voteAverage());
        assertEquals("科幻,悬疑", data.genres());
    }

    /**
     * 剧 NFO：tvshow 根元素映射为 tv；无 premiered 时回退 year。
     */
    @Test
    void shouldParseTvshowNfoWithYearFallback() {
        String xml = """
                <tvshow>
                  <title>亮剑</title>
                  <plot>抗战题材</plot>
                  <year>2005</year>
                </tvshow>
                """;
        MediaNfoSupport.NfoData data = nfoSupport.parse(xml);

        assertEquals("tv", data.mediaType());
        assertEquals("亮剑", data.title());
        assertEquals("2005", data.releaseDate());
        assertNull(data.tmdbId());
        assertNull(data.voteAverage());
    }

    /**
     * 集 NFO：episodedetails 含季号与集号。
     */
    @Test
    void shouldParseEpisodeNfo() {
        String xml = """
                <episodedetails>
                  <title>第一集</title>
                  <plot>开场</plot>
                  <season>1</season>
                  <episode>1</episode>
                  <rating>9.0</rating>
                </episodedetails>
                """;
        MediaNfoSupport.NfoData data = nfoSupport.parse(xml);

        assertEquals("episode", data.mediaType());
        assertEquals("第一集", data.title());
        assertEquals(1, data.seasonNo());
        assertEquals(1, data.episodeNo());
        assertEquals(9.0, data.voteAverage());
    }

    /**
     * 带 UTF-8 BOM（\uFEFF，Emby 等工具写出的 NFO）的剧 NFO 可完整解析。
     */
    @Test
    void shouldParseTvshowNfoWithBom() {
        String xml = "\uFEFF" + """
                <tvshow>
                  <title>亮剑</title>
                  <originaltitle>Bright Sword</originaltitle>
                  <plot>抗战题材</plot>
                  <premiered>2005-09-12</premiered>
                  <rating>9.2</rating>
                  <tmdbid>34567</tmdbid>
                  <genre>战争</genre>
                  <genre>剧情</genre>
                </tvshow>
                """;
        MediaNfoSupport.NfoData data = nfoSupport.parse(xml);

        assertEquals("tv", data.mediaType());
        assertEquals("亮剑", data.title());
        assertEquals("Bright Sword", data.originalTitle());
        assertEquals("抗战题材", data.overview());
        assertEquals("2005-09-12", data.releaseDate());
        assertEquals(9.2, data.voteAverage());
        assertEquals(34567L, data.tmdbId());
        assertEquals("战争,剧情", data.genres());
    }

    /**
     * 带 UTF-8 BOM 的集 NFO 可完整解析。
     */
    @Test
    void shouldParseEpisodeNfoWithBom() {
        String xml = "\uFEFF" + """
                <episodedetails>
                  <title>第一集</title>
                  <plot>开场</plot>
                  <season>1</season>
                  <episode>1</episode>
                  <rating>9.0</rating>
                  <tmdbid>888</tmdbid>
                </episodedetails>
                """;
        MediaNfoSupport.NfoData data = nfoSupport.parse(xml);

        assertEquals("episode", data.mediaType());
        assertEquals("第一集", data.title());
        assertEquals("开场", data.overview());
        assertEquals(1, data.seasonNo());
        assertEquals(1, data.episodeNo());
        assertEquals(9.0, data.voteAverage());
        assertEquals(888L, data.tmdbId());
    }

    /**
     * 非法 XML 与不支持的根元素返回 null，不抛异常。
     */
    @Test
    void shouldReturnNullForInvalidXml() {
        assertNull(nfoSupport.parse("not xml at all"));
        assertNull(nfoSupport.parse("<movie><title>未闭合"));
        assertNull(nfoSupport.parse("<music><title>歌</title></music>"));
        assertNull(nfoSupport.parse(null));
        assertNull(nfoSupport.parse(""));
    }

    /**
     * 缺字段的 NFO 返回部分解析结果。
     */
    @Test
    void shouldParsePartialNfo() {
        MediaNfoSupport.NfoData data = nfoSupport.parse("<movie><title>只有标题</title></movie>");

        assertEquals("movie", data.mediaType());
        assertEquals("只有标题", data.title());
        assertNull(data.overview());
        assertNull(data.releaseDate());
        assertNull(data.genres());
    }

    /**
     * 新模型生成与解析回读一致（roundtrip，根元素按 owner_type 派生）。
     */
    @Test
    void shouldGenerateAndParseBack() {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setOwnerType(MediaMetadataOwnerType.EPISODE.getCode());
        metadata.setTmdbId(123L);
        metadata.setTitle("第一集");
        metadata.setOriginalTitle("Episode One");
        metadata.setOverview("开场");
        metadata.setReleaseDate("2005-09-12");
        metadata.setVoteAverage(9.0);
        metadata.setGenres("剧情,战争");

        String xml = nfoSupport.generate(metadata, 1, 1);
        MediaNfoSupport.NfoData data = nfoSupport.parse(xml);

        assertEquals("episode", data.mediaType());
        assertEquals(123L, data.tmdbId());
        assertEquals("第一集", data.title());
        assertEquals("Episode One", data.originalTitle());
        assertEquals("开场", data.overview());
        assertEquals("2005-09-12", data.releaseDate());
        assertEquals(9.0, data.voteAverage());
        assertEquals("剧情,战争", data.genres());
        assertEquals(1, data.seasonNo());
        assertEquals(1, data.episodeNo());
    }

    /**
     * series owner 生成 tvshow 根元素。
     */
    @Test
    void shouldGenerateTvshowRoot() {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setOwnerType(MediaMetadataOwnerType.SERIES.getCode());
        metadata.setTitle("亮剑");

        MediaNfoSupport.NfoData data = nfoSupport.parse(nfoSupport.generate(metadata, null, null));

        assertEquals("tv", data.mediaType());
        assertEquals("亮剑", data.title());
    }

    /**
     * 附属文件命名规则。
     */
    @Test
    void shouldResolveArtworkNames() {
        assertEquals("亮剑.S01E01.nfo", nfoSupport.nfoNameOf("亮剑.S01E01.mkv"));
        assertEquals("亮剑.S01E01-thumb.jpg", nfoSupport.episodeThumbNameOf("亮剑.S01E01.mkv"));
        assertEquals("season01-poster.jpg", nfoSupport.seasonPosterName(1));
        assertEquals("season12-poster.jpg", nfoSupport.seasonPosterName(12));
        assertEquals("无名", nfoSupport.mainNameOf("无名"));
    }

    /**
     * 本地媒体图片命名链常量（ADR 0022）：海报/背景识别链内容完整有序，写回名为各自链首。
     */
    @Test
    void shouldExposeArtworkNameChains() {
        assertEquals(List.of("folder.jpg", "poster.jpg", "cover.jpg", "default.jpg", "movie.jpg"),
                MediaNfoSupport.MOVIE_POSTER_NAMES);
        assertEquals(List.of("folder.jpg", "poster.jpg", "cover.jpg", "default.jpg", "show.jpg"),
                MediaNfoSupport.TV_POSTER_NAMES);
        assertEquals(List.of("backdrop.jpg", "fanart.jpg", "background.jpg", "art.jpg"),
                MediaNfoSupport.BACKDROP_NAMES);
        assertEquals("folder.jpg", MediaNfoSupport.POSTER_WRITE_NAME);
        assertEquals("backdrop.jpg", MediaNfoSupport.BACKDROP_WRITE_NAME);
        // 写回名置于识别链首：保证写读自洽（重新削刮读到的是自己写回的图）
        assertEquals(MediaNfoSupport.POSTER_WRITE_NAME, MediaNfoSupport.MOVIE_POSTER_NAMES.get(0));
        assertEquals(MediaNfoSupport.POSTER_WRITE_NAME, MediaNfoSupport.TV_POSTER_NAMES.get(0));
        assertEquals(MediaNfoSupport.BACKDROP_WRITE_NAME, MediaNfoSupport.BACKDROP_NAMES.get(0));
    }
}
