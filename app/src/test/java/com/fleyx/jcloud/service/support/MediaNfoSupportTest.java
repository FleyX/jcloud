package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.model.po.MediaMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体 NFO 支撑组件测试（issue #21 起仅覆盖解析/命名/新模型生成路径，本地元数据 upsert 已随旧表弃用删除）。
 */
class MediaNfoSupportTest {

    private final MediaNfoSupport nfoSupport = new MediaNfoSupport(new MediaNfoMergeSupport());

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

    // ---------- NFO 合并写回（ADR 0033，工单 04） ----------

    /**
     * 现有 NFO 含未知元素（actor/uniqueid/studio）：合并后管理字段被覆盖，未知元素原样保留。
     */
    @Test
    void shouldMergePreserveUnknownFieldsAndOverrideManaged() {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setOwnerType(MediaMetadataOwnerType.MOVIE.getCode());
        metadata.setTmdbId(27205L);
        metadata.setTitle("新标题");
        metadata.setReleaseDate("2010-07-16");
        metadata.setVoteAverage(7.5);
        metadata.setGenres("科幻");
        String existing = """
                <movie>
                  <tmdbid>1</tmdbid>
                  <title>旧标题</title>
                  <rating>1.0</rating>
                  <actor><name>某演员</name><role>主角</role></actor>
                  <uniqueid type="imdb">tt1375666</uniqueid>
                  <studio>华纳</studio>
                </movie>
                """;

        String merged = nfoSupport.mergeNfo(existing, metadata, null, null);
        MediaNfoSupport.NfoData data = nfoSupport.parse(merged);

        assertEquals(27205L, data.tmdbId());
        assertEquals("新标题", data.title());
        assertEquals(7.5, data.voteAverage());
        assertEquals("科幻", data.genres());
        assertTrue(merged.contains("<actor>"));
        assertTrue(merged.contains("某演员"));
        assertTrue(merged.contains("tt1375666"));
        assertTrue(merged.contains("<studio>"));
        assertTrue(!merged.contains("旧标题"));
    }

    /**
     * 现有 NFO 缺失的管理字段在合并后被补上，根元素与无关元素保留。
     */
    @Test
    void shouldMergeAddMissingManagedFields() {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setOwnerType(MediaMetadataOwnerType.MOVIE.getCode());
        metadata.setTmdbId(999L);
        metadata.setTitle("新片");
        metadata.setOriginalTitle("New Movie");
        metadata.setOverview("简介");
        metadata.setReleaseDate("2020-01-02");
        metadata.setVoteAverage(8.0);
        metadata.setGenres("动作,冒险");

        String merged = nfoSupport.mergeNfo("<movie><streamdetails/></movie>", metadata, null, null);
        MediaNfoSupport.NfoData data = nfoSupport.parse(merged);

        assertEquals(999L, data.tmdbId());
        assertEquals("新片", data.title());
        assertEquals("New Movie", data.originalTitle());
        assertEquals("简介", data.overview());
        assertEquals("2020-01-02", data.releaseDate());
        assertEquals(8.0, data.voteAverage());
        assertEquals("动作,冒险", data.genres());
        assertTrue(merged.contains("streamdetails"));
    }

    /**
     * genre 整体替换而非并集：旧 genre 消失、新 genre 出现且无重复。
     */
    @Test
    void shouldMergeReplaceGenresWholly() {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setOwnerType(MediaMetadataOwnerType.MOVIE.getCode());
        metadata.setGenres("战争,剧情");

        String merged = nfoSupport.mergeNfo(
                "<movie><genre>科幻</genre><genre>悬疑</genre></movie>", metadata, null, null);
        MediaNfoSupport.NfoData data = nfoSupport.parse(merged);

        assertEquals("战争,剧情", data.genres());
        assertTrue(!merged.contains("科幻"));
        assertTrue(!merged.contains("悬疑"));
    }

    /**
     * metadata 无 genre 时整体替换为空：既有 genre 全部移除。
     */
    @Test
    void shouldMergeClearGenresWhenEmpty() {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setOwnerType(MediaMetadataOwnerType.MOVIE.getCode());

        String merged = nfoSupport.mergeNfo(
                "<movie><genre>科幻</genre></movie>", metadata, null, null);

        assertNull(nfoSupport.parse(merged).genres());
        assertTrue(!merged.contains("科幻"));
    }

    /**
     * 畸形 XML 回退整体重写：根元素为 movie、管理字段完整。
     */
    @Test
    void shouldMergeFallbackOnMalformedXml() {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setOwnerType(MediaMetadataOwnerType.MOVIE.getCode());
        metadata.setTmdbId(5L);
        metadata.setTitle("坏XML回退");

        String merged = nfoSupport.mergeNfo("not xml <<<", metadata, null, null);
        MediaNfoSupport.NfoData data = nfoSupport.parse(merged);

        assertEquals("movie", data.mediaType());
        assertEquals(5L, data.tmdbId());
        assertEquals("坏XML回退", data.title());
    }

    /**
     * 根元素与媒体类型不符（movie 元数据配 tvshow NFO）回退整体重写。
     */
    @Test
    void shouldMergeFallbackOnWrongRoot() {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setOwnerType(MediaMetadataOwnerType.MOVIE.getCode());
        metadata.setTmdbId(7L);
        metadata.setTitle("根不符回退");

        String merged = nfoSupport.mergeNfo("<tvshow><title>x</title></tvshow>", metadata, null, null);
        MediaNfoSupport.NfoData data = nfoSupport.parse(merged);

        assertEquals("movie", data.mediaType());
        assertEquals(7L, data.tmdbId());
        assertEquals("根不符回退", data.title());
    }

    /**
     * 无现有 NFO（空/空白）时行为与现状一致：全新生成。
     */
    @Test
    void shouldMergeGenerateWhenNoExisting() {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setOwnerType(MediaMetadataOwnerType.MOVIE.getCode());
        metadata.setTitle("全新");

        assertEquals("movie", nfoSupport.parse(nfoSupport.mergeNfo(null, metadata, null, null)).mediaType());
        assertEquals("movie", nfoSupport.parse(nfoSupport.mergeNfo("", metadata, null, null)).mediaType());
        assertEquals("movie", nfoSupport.parse(nfoSupport.mergeNfo("   ", metadata, null, null)).mediaType());
    }

    /**
     * season/episode 仅集类型写入；电影类型不变（不写也不清）。
     */
    @Test
    void shouldMergeSeasonEpisodeOnlyForEpisode() {
        MediaMetadata episodeMeta = new MediaMetadata();
        episodeMeta.setOwnerType(MediaMetadataOwnerType.EPISODE.getCode());
        episodeMeta.setTitle("集");
        MediaMetadata movieMeta = new MediaMetadata();
        movieMeta.setOwnerType(MediaMetadataOwnerType.MOVIE.getCode());
        movieMeta.setTitle("片");

        MediaNfoSupport.NfoData edata = nfoSupport.parse(
                nfoSupport.mergeNfo("<episodedetails/>", episodeMeta, 3, 4));
        assertEquals(3, edata.seasonNo());
        assertEquals(4, edata.episodeNo());

        MediaNfoSupport.NfoData mdata = nfoSupport.parse(
                nfoSupport.mergeNfo("<movie/>", movieMeta, 3, 4));
        assertNull(mdata.seasonNo());
        assertNull(mdata.episodeNo());
    }

    /**
     * 管理标量字段值为 null（如 title/plot/rating/releaseDate 缺失）时删除既有同名元素，
     * 对齐「jcloud 为准覆盖」与 genre 整体替换语义。
     */
    @Test
    void shouldMergeDeleteManagedFieldsWhenValueNull() {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setOwnerType(MediaMetadataOwnerType.MOVIE.getCode());
        metadata.setTmdbId(42L);
        String existing = """
                <movie>
                  <tmdbid>1</tmdbid>
                  <title>旧标题</title>
                  <plot>旧简介</plot>
                  <year>2010</year>
                  <premiered>2010-01-01</premiered>
                  <rating>7.0</rating>
                  <genre>科幻</genre>
                </movie>
                """;

        String merged = nfoSupport.mergeNfo(existing, metadata, null, null);
        MediaNfoSupport.NfoData data = nfoSupport.parse(merged);

        // 值非空字段照常覆盖（tmdbid 更新为 42）
        assertEquals(42L, data.tmdbId());
        // 管理标量值为 null 时删除既有旧元素（title/plot/rating/releaseDate→year/premiered）
        assertNull(data.title());
        assertNull(data.overview());
        assertNull(data.voteAverage());
        assertNull(data.releaseDate());
        assertTrue(!merged.contains("<title>"));
        assertTrue(!merged.contains("<plot>"));
        assertTrue(!merged.contains("<year>"));
        assertTrue(!merged.contains("<premiered>"));
        assertTrue(!merged.contains("<rating>"));
        // genre 无值时整体替换为空
        assertTrue(!merged.contains("科幻"));
    }

    /**
     * 同名管理元素存在多个时：更新第一个、删除其余，收敛为单值。
     */
    @Test
    void shouldMergeConvergeDuplicateManagedElements() {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setOwnerType(MediaMetadataOwnerType.MOVIE.getCode());
        metadata.setTmdbId(77L);
        metadata.setTitle("收敛标题");
        metadata.setReleaseDate("2021-05-06");
        String existing = """
                <movie>
                  <title>旧一</title>
                  <title>旧二</title>
                  <title>旧三</title>
                  <tmdbid>5</tmdbid>
                </movie>
                """;

        String merged = nfoSupport.mergeNfo(existing, metadata, null, null);

        assertEquals("收敛标题", nfoSupport.parse(merged).title());
        // 同名元素多个：更新第一个、删除其余，收敛为单值
        assertEquals(1, occurrences(merged, "<title>"));
        assertEquals(77L, nfoSupport.parse(merged).tmdbId());
        // releaseDate 派生 year/premiered 均为单值
        assertEquals(1, occurrences(merged, "<year>"));
        assertEquals(1, occurrences(merged, "<premiered>"));
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }
}
