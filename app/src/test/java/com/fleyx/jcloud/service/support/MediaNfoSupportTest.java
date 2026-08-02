package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.model.po.MediaMetadata;
import org.junit.jupiter.api.Test;

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
}
