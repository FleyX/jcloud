package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesVo;
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
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaItemServiceTest {

    @Autowired
    private MediaItemService mediaItemService;

    @Autowired
    private MediaSeriesMapper mediaSeriesMapper;

    @Autowired
    private MediaSeasonMapper mediaSeasonMapper;

    @Autowired
    private MediaItemMapper mediaItemMapper;

    /**
     * 剧详情返回季卡片：季号升序、未知季排最后，含集数与观看进度标记。
     */
    @Test
    void shouldReturnSeriesDetailWithSeasons() {
        MediaSeries series = insertSeries("user-1", "测试剧");
        MediaSeason season1 = insertSeason(series, 1);
        MediaSeason season2 = insertSeason(series, 2);
        MediaSeason unknownSeason = insertSeason(series, null);
        insertEpisode("user-1", series, season1, 1, 1, 0L);
        insertEpisode("user-1", series, season1, 1, 2, 0L);
        insertEpisode("user-1", series, season2, 2, 1, 100L);

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
        MediaSeries series = insertSeries("user-1", "测试剧");
        MediaSeason season1 = insertSeason(series, 1);
        MediaSeason season2 = insertSeason(series, 2);
        insertEpisode("user-1", series, season1, 1, 2, 0L);
        insertEpisode("user-1", series, season1, 1, 1, 0L);
        insertEpisode("user-1", series, season2, 2, 1, 0L);

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
        MediaSeries series = insertSeries("user-1", "测试剧");

        assertThrows(BusinessException.class,
                () -> mediaItemService.getSeriesDetail(series.getId(), "user-2"));
    }

    /**
     * 电影海报墙按媒体库过滤。
     */
    @Test
    void shouldFilterMoviesByDirectoryId() {
        insertMovie("user-1", "dir-1", 1000L);
        insertMovie("user-1", "dir-2", 2000L);

        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setDirectoryId("dir-1");
        assertEquals(1L, mediaItemService.listMovies("user-1", query).getTotal());
        assertEquals(2L, mediaItemService.listMovies("user-1", new MediaPageQueryDto()).getTotal());
    }

    /**
     * 电视剧海报墙按媒体库过滤（按剧下条目归属判断）。
     */
    @Test
    void shouldFilterSeriesByDirectoryId() {
        MediaSeries seriesA = insertSeries("user-1", "剧一");
        insertEpisode("user-1", "dir-1", seriesA, insertSeason(seriesA, 1), 1, 1, 0L);
        MediaSeries seriesB = insertSeries("user-1", "剧二");
        insertEpisode("user-1", "dir-2", seriesB, insertSeason(seriesB, 1), 1, 1, 0L);

        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setDirectoryId("dir-1");
        IPage<MediaSeriesVo> page = mediaItemService.listSeries("user-1", query);

        assertEquals(1L, page.getTotal());
        assertEquals("剧一", page.getRecords().getFirst().getSeriesName());
        assertEquals(2L, mediaItemService.listSeries("user-1", new MediaPageQueryDto()).getTotal());
    }

    private MediaSeries insertSeries(String userId, String seriesName) {
        MediaSeries series = new MediaSeries();
        series.setUserId(userId);
        series.setSeriesName(seriesName);
        series.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
        mediaSeriesMapper.insert(series);
        return series;
    }

    private MediaSeason insertSeason(MediaSeries series, Integer seasonNo) {
        MediaSeason season = new MediaSeason();
        season.setSeriesId(series.getId());
        season.setSeasonNo(seasonNo);
        mediaSeasonMapper.insert(season);
        return season;
    }

    private void insertEpisode(String userId, MediaSeries series, MediaSeason season,
                               int seasonNo, int episodeNo, long progressMs) {
        insertEpisode(userId, "dir-1", series, season, seasonNo, episodeNo, progressMs);
    }

    private void insertEpisode(String userId, String directoryId, MediaSeries series, MediaSeason season,
                               int seasonNo, int episodeNo, long progressMs) {
        MediaItem item = new MediaItem();
        item.setUserId(userId);
        item.setDirectoryId(directoryId);
        item.setFileNodeId("file-" + seasonNo + "-" + episodeNo);
        item.setItemType(MediaItemType.EPISODE.getCode());
        item.setSeriesName(series.getSeriesName());
        item.setSeriesId(series.getId());
        item.setSeasonId(season.getId());
        item.setSeasonNo(seasonNo);
        item.setEpisodeNo(episodeNo);
        item.setProgressMs(progressMs);
        item.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
        mediaItemMapper.insert(item);
    }

    private void insertMovie(String userId, String directoryId, long fileLastModified) {
        MediaItem item = new MediaItem();
        item.setUserId(userId);
        item.setDirectoryId(directoryId);
        item.setFileNodeId("fm-" + directoryId);
        item.setItemType(MediaItemType.MOVIE.getCode());
        item.setFileLastModified(fileLastModified);
        item.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
        mediaItemMapper.insert(item);
    }
}
