package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.mapper.MediaSeriesV2Mapper;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.po.MediaSeriesV2;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.MediaArtworkPersistSupport;
import com.fleyx.jcloud.service.support.MediaItemVoSupport;
import com.fleyx.jcloud.service.support.MediaMovieQuerySupport;
import com.fleyx.jcloud.service.support.MediaOtherQuerySupport;
import com.fleyx.jcloud.service.support.MediaPlaybackResolveSupport;
import com.fleyx.jcloud.service.support.MediaSeriesSupport;
import com.fleyx.jcloud.service.support.MediaTvQuerySupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 媒体条目查询服务实现测试（issue #19：按文件节点反查标题级行 ID）。
 */
class MediaItemServiceImplTest {

    private final MediaItemMapper mediaItemMapper = mock(MediaItemMapper.class);
    private final MediaMetadataMapper mediaMetadataMapper = mock(MediaMetadataMapper.class);
    private final MediaSeriesMapper mediaSeriesMapper = mock(MediaSeriesMapper.class);
    private final MediaMovieMapper mediaMovieMapper = mock(MediaMovieMapper.class);
    private final MediaMovieFileMapper mediaMovieFileMapper = mock(MediaMovieFileMapper.class);
    private final MediaEpisodeMapper mediaEpisodeMapper = mock(MediaEpisodeMapper.class);
    private final MediaEpisodeFileMapper mediaEpisodeFileMapper = mock(MediaEpisodeFileMapper.class);
    private final MediaOtherMapper mediaOtherMapper = mock(MediaOtherMapper.class);
    private final MediaSeriesV2Mapper mediaSeriesV2Mapper = mock(MediaSeriesV2Mapper.class);
    private final FileMapper fileMapper = mock(FileMapper.class);
    private final TmdbService tmdbService = mock(TmdbService.class);
    private final MediaSeriesSupport mediaSeriesSupport = mock(MediaSeriesSupport.class);
    private final MediaItemVoSupport mediaItemVoSupport = mock(MediaItemVoSupport.class);
    private final MediaArtworkPersistSupport mediaArtworkPersistSupport = mock(MediaArtworkPersistSupport.class);
    private final MediaTvQuerySupport mediaTvQuerySupport = mock(MediaTvQuerySupport.class);
    private final MediaMovieQuerySupport mediaMovieQuerySupport = mock(MediaMovieQuerySupport.class);
    private final MediaOtherQuerySupport mediaOtherQuerySupport = mock(MediaOtherQuerySupport.class);
    private final MediaPlaybackResolveSupport mediaPlaybackResolveSupport = mock(MediaPlaybackResolveSupport.class);

    private final MediaItemServiceImpl mediaItemService = new MediaItemServiceImpl(
            mediaItemMapper, mediaMetadataMapper, mediaSeriesMapper, mediaMovieMapper,
            mediaMovieFileMapper, mediaEpisodeMapper, mediaEpisodeFileMapper, mediaOtherMapper,
            mediaSeriesV2Mapper, fileMapper, tmdbService, mediaSeriesSupport, mediaItemVoSupport,
            mediaArtworkPersistSupport, mediaTvQuerySupport, mediaMovieQuerySupport, mediaOtherQuerySupport,
            mediaPlaybackResolveSupport);

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
        MediaSeriesV2 series = new MediaSeriesV2();
        series.setId("series-1");
        series.setUserId("user-1");
        when(mediaEpisodeFileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(episodeFile);
        when(mediaEpisodeMapper.selectById("episode-1")).thenReturn(episode);
        when(mediaSeriesV2Mapper.selectById("series-1")).thenReturn(series);

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
        when(mediaItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> mediaItemService.getItemIdByFileNodeId("fn-9", "user-1"));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
    }
}
