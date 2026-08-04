package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.MediaArtworkPersistV2Support;
import com.fleyx.jcloud.service.support.MediaGenreSupport;
import com.fleyx.jcloud.service.support.MediaMetadataCompleteSupport;
import com.fleyx.jcloud.service.support.MediaMetadataSupport;
import com.fleyx.jcloud.service.support.MediaMovieQuerySupport;
import com.fleyx.jcloud.service.support.MediaOtherQuerySupport;
import com.fleyx.jcloud.service.support.MediaPlaybackResolveSupport;
import com.fleyx.jcloud.service.support.MediaTvQuerySupport;
import com.fleyx.jcloud.service.support.MediaTvScrapeSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 媒体条目查询服务实现测试（issue #19：按文件节点反查标题级行 ID；issue #21：统一按行 ID 手动修正）。
 */
class MediaItemServiceImplTest {

    private final MediaSeriesMapper mediaSeriesMapper = mock(MediaSeriesMapper.class);
    private final MediaMovieMapper mediaMovieMapper = mock(MediaMovieMapper.class);
    private final MediaMovieFileMapper mediaMovieFileMapper = mock(MediaMovieFileMapper.class);
    private final MediaEpisodeMapper mediaEpisodeMapper = mock(MediaEpisodeMapper.class);
    private final MediaEpisodeFileMapper mediaEpisodeFileMapper = mock(MediaEpisodeFileMapper.class);
    private final MediaOtherMapper mediaOtherMapper = mock(MediaOtherMapper.class);
    private final TmdbService tmdbService = mock(TmdbService.class);
    private final MediaTvQuerySupport mediaTvQuerySupport = mock(MediaTvQuerySupport.class);
    private final MediaMovieQuerySupport mediaMovieQuerySupport = mock(MediaMovieQuerySupport.class);
    private final MediaOtherQuerySupport mediaOtherQuerySupport = mock(MediaOtherQuerySupport.class);
    private final MediaPlaybackResolveSupport mediaPlaybackResolveSupport = mock(MediaPlaybackResolveSupport.class);
    private final MediaMetadataSupport metadataSupport = mock(MediaMetadataSupport.class);
    private final MediaMetadataCompleteSupport metadataCompleteSupport = mock(MediaMetadataCompleteSupport.class);
    private final MediaTvScrapeSupport mediaTvScrapeSupport = mock(MediaTvScrapeSupport.class);
    private final MediaArtworkPersistV2Support artworkPersistV2Support = mock(MediaArtworkPersistV2Support.class);
    private final MediaGenreSupport mediaGenreSupport = mock(MediaGenreSupport.class);

    private final MediaItemServiceImpl mediaItemService = new MediaItemServiceImpl(
            mediaSeriesMapper, mediaMovieMapper, mediaMovieFileMapper,
            mediaEpisodeMapper, mediaEpisodeFileMapper, mediaOtherMapper,
            tmdbService, mediaTvQuerySupport, mediaMovieQuerySupport, mediaOtherQuerySupport,
            mediaPlaybackResolveSupport, metadataSupport, metadataCompleteSupport,
            mediaTvScrapeSupport, artworkPersistV2Support, mediaGenreSupport);

    /**
     * 按文件节点 ID 反查：其他库文件命中 other 行时返回 other 行 ID。
     */
    @Test
    void shouldReturnOtherIdByFileNodeId() {
        MediaOther other = new MediaOther();
        other.setId("other-1");
        other.setFileNodeId("fn-1");
        other.setUserId("user-1");
        when(mediaOtherMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(other);

        String id = mediaItemService.getItemIdByFileNodeId("fn-1", "user-1");

        assertEquals("other-1", id);
    }

    /**
     * 按文件节点 ID 反查：电影文件明细命中时返回电影标题级 ID（多版本共享）。
     */
    @Test
    void shouldReturnMovieIdByFileNodeId() {
        MediaMovieFile movieFile = new MediaMovieFile();
        movieFile.setId("movie-file-1");
        movieFile.setFileNodeId("fn-movie");
        movieFile.setMovieId("movie-1");
        MediaMovie movie = new MediaMovie();
        movie.setId("movie-1");
        movie.setUserId("user-1");
        when(mediaMovieFileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(movieFile);
        when(mediaMovieMapper.selectById("movie-1")).thenReturn(movie);

        String id = mediaItemService.getItemIdByFileNodeId("fn-movie", "user-1");

        assertEquals("movie-1", id);
    }

    /**
     * 按文件节点 ID 反查：集文件明细命中时返回集标题级 ID。
     */
    @Test
    void shouldReturnEpisodeIdByFileNodeId() {
        MediaEpisodeFile episodeFile = new MediaEpisodeFile();
        episodeFile.setId("ep-file-1");
        episodeFile.setFileNodeId("fn-ep");
        episodeFile.setEpisodeId("episode-1");
        MediaEpisode episode = new MediaEpisode();
        episode.setId("episode-1");
        episode.setSeriesId("series-1");
        MediaSeries series = new MediaSeries();
        series.setId("series-1");
        series.setUserId("user-1");
        when(mediaEpisodeFileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(episodeFile);
        when(mediaEpisodeMapper.selectById("episode-1")).thenReturn(episode);
        when(mediaSeriesMapper.selectById("series-1")).thenReturn(series);

        String id = mediaItemService.getItemIdByFileNodeId("fn-ep", "user-1");

        assertEquals("episode-1", id);
    }

    /**
     * 按文件节点 ID 反查：全部未命中时抛 NOT_FOUND 业务异常。
     */
    @Test
    void shouldThrowNotFoundWhenNothingMatched() {
        when(mediaOtherMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(mediaMovieFileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(mediaEpisodeFileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> mediaItemService.getItemIdByFileNodeId("fn-9", "user-1"));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
    }

    /**
     * 手动修正：剧集行 ID 命中时走剧集级修正，返回剧集行视图。
     */
    @Test
    void shouldMatchSeriesRowById() {
        MediaSeries series = new MediaSeries();
        series.setId("series-1");
        series.setUserId("user-1");
        series.setSeriesName("测试剧");
        when(mediaMovieMapper.selectById("series-1")).thenReturn(null);
        when(mediaSeriesMapper.selectById("series-1")).thenReturn(series);

        com.fleyx.jcloud.model.dto.MediaMatchUpdateDto dto = new com.fleyx.jcloud.model.dto.MediaMatchUpdateDto();
        dto.setTmdbId(100L);
        dto.setMediaType("tv");
        MediaMetadata detached = new MediaMetadata();
        detached.setTitle("测试剧");
        when(tmdbService.fetchDetailV2(eq("user-1"), eq(100L), eq("tv"))).thenReturn(detached);

        var vo = mediaItemService.updateMatch("series-1", dto, "user-1");

        assertEquals("series-1", vo.getId());
        assertEquals("series", vo.getItemType());
    }

    /**
     * 手动修正：集行 ID 命中时集级手动修正已下线，抛业务异常。
     */
    @Test
    void shouldRejectEpisodeMatch() {
        MediaEpisode episode = new MediaEpisode();
        episode.setId("episode-1");
        when(mediaMovieMapper.selectById("episode-1")).thenReturn(null);
        when(mediaSeriesMapper.selectById("episode-1")).thenReturn(null);
        when(mediaEpisodeMapper.selectById("episode-1")).thenReturn(episode);

        com.fleyx.jcloud.model.dto.MediaMatchUpdateDto dto = new com.fleyx.jcloud.model.dto.MediaMatchUpdateDto();
        dto.setTmdbId(100L);
        dto.setMediaType("tv");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> mediaItemService.updateMatch("episode-1", dto, "user-1"));

        assertEquals(ResultCode.BUSINESS_ERROR, exception.getResultCode());
    }
}
