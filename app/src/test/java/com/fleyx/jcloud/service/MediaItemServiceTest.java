package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaSeasonV2Mapper;
import com.fleyx.jcloud.mapper.MediaSeriesV2Mapper;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaSeasonV2;
import com.fleyx.jcloud.model.po.MediaSeriesV2;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesVo;
import com.fleyx.jcloud.util.IdUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体条目查询服务测试。
 * <p>
 * 电视剧相关查询已切换到新模型表（issue #17）：t_media_series_v2 / t_media_season_v2 / t_media_episode。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaItemServiceTest {

    @Autowired
    private MediaItemService mediaItemService;

    @Autowired
    private MediaSeriesV2Mapper mediaSeriesV2Mapper;

    @Autowired
    private MediaSeasonV2Mapper mediaSeasonV2Mapper;

    @Autowired
    private MediaEpisodeMapper mediaEpisodeMapper;

    @Autowired
    private MediaMovieMapper mediaMovieMapper;

    /**
     * 剧详情返回季卡片：季号升序、未知季排最后，含集数与观看进度标记。
     */
    @Test
    void shouldReturnSeriesDetailWithSeasons() {
        MediaSeriesV2 series = insertSeries("user-1", "dir-1", "测试剧");
        MediaSeasonV2 season1 = insertSeason(series, 1);
        MediaSeasonV2 season2 = insertSeason(series, 2);
        MediaSeasonV2 unknownSeason = insertSeason(series, null);
        insertEpisode(series, season1, 1, 0L);
        insertEpisode(series, season1, 2, 0L);
        insertEpisode(series, season2, 1, 100L);

        MediaSeriesDetailVo detail = mediaItemService.getSeriesDetail(series.getId(), "user-1");

        assertEquals("测试剧", detail.getSeriesName());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), detail.getMatchStatus());
        assertEquals(3, detail.getSeasons().size());
        assertEquals(1, detail.getSeasons().get(0).getSeasonNo());
        assertEquals(2, detail.getSeasons().get(1).getSeasonNo());
        assertNull(detail.getSeasons().get(2).getSeasonNo());
        assertEquals(unknownSeason.getId(), detail.getSeasons().get(2).getSeasonId());
        assertEquals(2, detail.getSeasons().get(0).getEpisodeCount());
        assertFalse(detail.getSeasons().get(0).getHasProgress());
        assertTrue(detail.getSeasons().get(1).getHasProgress());
    }

    /**
     * 按季查询剧集：只返回该季的集并按集号升序；季不属于该剧或用户越权时抛业务异常。
     */
    @Test
    void shouldListSeasonEpisodesOrderedByEpisodeNo() {
        MediaSeriesV2 series = insertSeries("user-1", "dir-1", "测试剧");
        MediaSeasonV2 season1 = insertSeason(series, 1);
        MediaSeasonV2 season2 = insertSeason(series, 2);
        insertEpisode(series, season1, 2, 0L);
        insertEpisode(series, season1, 1, 0L);
        insertEpisode(series, season2, 1, 0L);

        List<MediaItemVo> episodes = mediaItemService.listSeasonEpisodes(series.getId(), season1.getId(), "user-1");

        assertEquals(2, episodes.size());
        assertEquals(1, episodes.get(0).getEpisodeNo());
        assertEquals(2, episodes.get(1).getEpisodeNo());
        assertThrows(BusinessException.class,
                () -> mediaItemService.listSeasonEpisodes(series.getId(), season2.getId(), "user-2"));
        assertThrows(BusinessException.class,
                () -> mediaItemService.listSeasonEpisodes(series.getId(), "not-exists", "user-1"));
    }

    @Test
    void shouldThrowWhenSeriesNotFound() {
        assertThrows(BusinessException.class,
                () -> mediaItemService.getSeriesDetail("not-exists", "user-1"));
    }

    @Test
    void shouldThrowWhenSeriesNotOwned() {
        MediaSeriesV2 series = insertSeries("user-1", "dir-1", "测试剧");

        assertThrows(BusinessException.class,
                () -> mediaItemService.getSeriesDetail(series.getId(), "user-2"));
    }

    /**
     * 电影海报墙按媒体库过滤（新模型 t_media_movie，issue #18）。
     */
    @Test
    void shouldFilterMoviesByDirectoryId() {
        insertMovie("user-1", "dir-1");
        insertMovie("user-1", "dir-2");

        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setDirectoryId("dir-1");
        assertEquals(1L, mediaItemService.listMovies("user-1", query).getTotal());
        assertEquals(2L, mediaItemService.listMovies("user-1", new MediaPageQueryDto()).getTotal());
    }

    /**
     * 电视剧海报墙按媒体库过滤（剧行直接归属媒体库）。
     */
    @Test
    void shouldFilterSeriesByDirectoryId() {
        insertSeries("user-1", "dir-1", "剧一");
        insertSeries("user-1", "dir-2", "剧二");

        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setDirectoryId("dir-1");
        IPage<MediaSeriesVo> page = mediaItemService.listSeries("user-1", query);

        assertEquals(1L, page.getTotal());
        assertEquals("剧一", page.getRecords().getFirst().getSeriesName());
        assertEquals(2L, mediaItemService.listSeries("user-1", new MediaPageQueryDto()).getTotal());
    }

    private MediaSeriesV2 insertSeries(String userId, String directoryId, String seriesName) {
        MediaSeriesV2 series = new MediaSeriesV2();
        series.setUserId(userId);
        series.setDirectoryId(directoryId);
        series.setFolderNodeId(IdUtil.nextId());
        series.setSeriesName(seriesName);
        series.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
        mediaSeriesV2Mapper.insert(series);
        return series;
    }

    private MediaSeasonV2 insertSeason(MediaSeriesV2 series, Integer seasonNo) {
        MediaSeasonV2 season = new MediaSeasonV2();
        season.setSeriesId(series.getId());
        season.setFolderNodeId(IdUtil.nextId());
        season.setSeasonNo(seasonNo);
        mediaSeasonV2Mapper.insert(season);
        return season;
    }

    private void insertEpisode(MediaSeriesV2 series, MediaSeasonV2 season, int episodeNo, long progressMs) {
        MediaEpisode episode = new MediaEpisode();
        episode.setSeriesId(series.getId());
        episode.setSeasonId(season.getId());
        episode.setEpisodeNo(episodeNo);
        episode.setProgressMs(progressMs);
        mediaEpisodeMapper.insert(episode);
    }

    private void insertMovie(String userId, String directoryId) {
        MediaMovie movie = new MediaMovie();
        movie.setUserId(userId);
        movie.setDirectoryId(directoryId);
        movie.setFolderNodeId(IdUtil.nextId());
        movie.setTitle("测试电影");
        movie.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
        movie.setMetadataComplete(false);
        mediaMovieMapper.insert(movie);
    }
}
