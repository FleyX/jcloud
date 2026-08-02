package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.MediaCompleteStatus;
import com.fleyx.jcloud.common.enums.MediaMetadataSource;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.model.po.MediaMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 媒体 NFO 支撑组件测试。
 */
class MediaNfoSupportTest {

    private final MediaMetadataMapper mediaMetadataMapper = mock(MediaMetadataMapper.class);
    private final MediaNfoSupport nfoSupport = new MediaNfoSupport(mediaMetadataMapper);

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
     * 生成与解析回读一致（roundtrip）。
     */
    @Test
    void shouldGenerateAndParseBack() {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setMediaType("episode");
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
     * tv 元数据生成 tvshow 根元素。
     */
    @Test
    void shouldGenerateTvshowRoot() {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setMediaType("tv");
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
     * 本地元数据 upsert：未绑定时新建 local_nfo 行，字段与完整性正确。
     */
    @Test
    void shouldUpsertNewLocalMetadata() {
        MediaNfoSupport.NfoData data = new MediaNfoSupport.NfoData("movie", "盗梦空间", "Inception",
                "造梦师盗取机密", "2010-07-16", 8.8, "科幻", 27205L, null, null);

        nfoSupport.upsertLocalMetadata(null, "user-1", "movie", data, "fnposter00001", null);

        ArgumentCaptor<MediaMetadata> captor = ArgumentCaptor.forClass(MediaMetadata.class);
        verify(mediaMetadataMapper).insert(captor.capture());
        MediaMetadata inserted = captor.getValue();
        assertEquals("user-1", inserted.getUserId());
        assertEquals(MediaMetadataSource.LOCAL_NFO.getCode(), inserted.getSource());
        assertEquals(27205L, inserted.getTmdbId());
        assertEquals("盗梦空间", inserted.getTitle());
        assertEquals("fnposter00001", inserted.getPosterFileNodeId());
        assertEquals(MediaCompleteStatus.COMPLETE.getCode(), inserted.getCompleteStatus());
    }

    /**
     * 完整性规则：标题、简介、海报任一缺失即 incomplete。
     */
    @Test
    void shouldMarkIncompleteWhenAnyKeyFieldMissing() {
        MediaNfoSupport.NfoData data = new MediaNfoSupport.NfoData("movie", "盗梦空间", null,
                null, null, null, null, null, null, null);

        nfoSupport.upsertLocalMetadata(null, "user-1", "movie", data, "fnposter00001", null);

        ArgumentCaptor<MediaMetadata> captor = ArgumentCaptor.forClass(MediaMetadata.class);
        verify(mediaMetadataMapper).insert(captor.capture());
        assertEquals(MediaCompleteStatus.INCOMPLETE.getCode(), captor.getValue().getCompleteStatus());
    }

    /**
     * 已绑定 local_nfo 行时原地更新而非新建。
     */
    @Test
    void shouldUpdateExistingLocalMetadata() {
        MediaMetadata existing = new MediaMetadata();
        existing.setId("meta0000000001");
        existing.setSource(MediaMetadataSource.LOCAL_NFO.getCode());
        when(mediaMetadataMapper.selectById("meta0000000001")).thenReturn(existing);
        MediaNfoSupport.NfoData data = new MediaNfoSupport.NfoData("movie", "新标题", null,
                "简介", null, null, null, null, null, null);

        MediaMetadata result = nfoSupport.upsertLocalMetadata("meta0000000001", "user-1", "movie", data,
                "fnposter00001", null);

        assertEquals("meta0000000001", result.getId());
        assertEquals("新标题", result.getTitle());
        verify(mediaMetadataMapper).updateById(any(MediaMetadata.class));
    }

    /**
     * 仅有本地图片无 NFO 时使用空 NFO 数据：全字段为空、完整性为不完整，不补文本字段。
     */
    @Test
    void shouldUpsertEmptyDataAsIncompleteLocalMetadata() {
        MediaNfoSupport.NfoData empty = MediaNfoSupport.emptyData("movie");

        nfoSupport.upsertLocalMetadata(null, "user-1", "movie", empty, "fnposter00001", null);

        ArgumentCaptor<MediaMetadata> captor = ArgumentCaptor.forClass(MediaMetadata.class);
        verify(mediaMetadataMapper).insert(captor.capture());
        MediaMetadata inserted = captor.getValue();
        assertEquals("local_nfo", inserted.getSource());
        assertEquals("incomplete", inserted.getCompleteStatus());
        assertNull(inserted.getTitle());
        assertNull(inserted.getTmdbId());
        assertEquals("fnposter00001", inserted.getPosterFileNodeId());
        assertEquals("user-1", inserted.getUserId());
    }
}
