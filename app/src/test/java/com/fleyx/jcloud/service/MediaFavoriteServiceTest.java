package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.enums.MediaFavoriteOwnerType;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaFavoriteMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaFavorite;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesSeasonVo;
import com.fleyx.jcloud.service.support.MediaMovieCascadeSupport;
import com.fleyx.jcloud.service.support.MediaOtherCascadeSupport;
import com.fleyx.jcloud.service.support.MediaTvCascadeSupport;
import com.fleyx.jcloud.util.IdUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体收藏服务测试（工单 01：五类实体收藏端到端）。
 * <p>
 * 覆盖：toggle 新增/取消/幂等与唯一约束、五类实体、用户隔离、VO favorited 填充、
 * 各级联删除路径（删电影/删剧集含季集/删其他/媒体库删除/即时 reconcile 清理）后收藏消失。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaFavoriteServiceTest {

    @Autowired
    private MediaFavoriteService mediaFavoriteService;

    @Autowired
    private MediaItemService mediaItemService;

    @Autowired
    private MediaFavoriteMapper mediaFavoriteMapper;

    @Autowired
    private MediaMovieMapper mediaMovieMapper;

    @Autowired
    private MediaSeriesMapper mediaSeriesMapper;

    @Autowired
    private MediaSeasonMapper mediaSeasonMapper;

    @Autowired
    private MediaEpisodeMapper mediaEpisodeMapper;

    @Autowired
    private MediaOtherMapper mediaOtherMapper;

    @Autowired
    private MediaMovieCascadeSupport mediaMovieCascadeSupport;

    @Autowired
    private MediaTvCascadeSupport mediaTvCascadeSupport;

    @Autowired
    private MediaOtherCascadeSupport mediaOtherCascadeSupport;

    /**
     * toggle 新增/取消交替，重复 toggle 幂等；直接插入重复唯一键抛 DuplicateKeyException（约束兜底）。
     */
    @Test
    void toggleSwitchesStateAndDuplicateKeyIsRejected() {
        MediaMovie movie = insertMovie("user-1", "dir-1", "测试电影", MediaMatchStatus.UNMATCHED.getCode());

        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.MOVIE, movie.getId()));
        assertFalse(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.MOVIE, movie.getId()));
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.MOVIE, movie.getId()));
        assertEquals(1, countFavorites("user-1", MediaFavoriteOwnerType.MOVIE, movie.getId()));

        // 唯一约束兜底：绕过 service 直接插入同 (user, ownerType, ownerId) 行
        MediaFavorite duplicate = new MediaFavorite();
        duplicate.setId(IdUtil.nextId());
        duplicate.setUserId("user-1");
        duplicate.setOwnerType(MediaFavoriteOwnerType.MOVIE.getCode());
        duplicate.setOwnerId(movie.getId());
        assertThrows(DuplicateKeyException.class, () -> mediaFavoriteMapper.insert(duplicate));
    }

    /**
     * 五类实体（电影/剧集/季/集/其他）均可收藏。
     */
    @Test
    void toggleSupportsAllFiveEntityTypes() {
        MediaMovie movie = insertMovie("user-1", "dir-1", "测试电影", MediaMatchStatus.UNMATCHED.getCode());
        MediaSeries series = insertSeries("user-1", "dir-1", "测试剧");
        MediaSeason season = insertSeason(series, 1);
        MediaEpisode episode = insertEpisode(series, season, 1);
        MediaOther other = insertOther("user-1", "dir-1");

        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.MOVIE, movie.getId()));
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.SERIES, series.getId()));
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.SEASON, season.getId()));
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.EPISODE, episode.getId()));
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.OTHER, other.getId()));

        assertEquals(Set.of(movie.getId()),
                mediaFavoriteService.listFavoritedOwnerIds("user-1", MediaFavoriteOwnerType.MOVIE,
                        List.of(movie.getId(), "not-exists")));
        assertEquals(Set.of(series.getId()),
                mediaFavoriteService.listFavoritedOwnerIds("user-1", MediaFavoriteOwnerType.SERIES,
                        List.of(series.getId())));
        assertEquals(Set.of(season.getId()),
                mediaFavoriteService.listFavoritedOwnerIds("user-1", MediaFavoriteOwnerType.SEASON,
                        List.of(season.getId())));
        assertEquals(Set.of(episode.getId()),
                mediaFavoriteService.listFavoritedOwnerIds("user-1", MediaFavoriteOwnerType.EPISODE,
                        List.of(episode.getId())));
        assertEquals(Set.of(other.getId()),
                mediaFavoriteService.listFavoritedOwnerIds("user-1", MediaFavoriteOwnerType.OTHER,
                        List.of(other.getId())));
    }

    /**
     * 按用户隔离：他人不可收藏他人的实体（BusinessException），不可见他人收藏。
     */
    @Test
    void favoritesAreIsolatedPerUser() {
        MediaMovie movie = insertMovie("user-1", "dir-1", "测试电影", MediaMatchStatus.UNMATCHED.getCode());
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.MOVIE, movie.getId()));

        assertThrows(BusinessException.class,
                () -> mediaFavoriteService.toggle("user-2", MediaFavoriteOwnerType.MOVIE, movie.getId()));
        assertTrue(mediaFavoriteService.listFavoritedOwnerIds("user-2", MediaFavoriteOwnerType.MOVIE,
                List.of(movie.getId())).isEmpty());

        IPage<MediaItemVo> page = mediaItemService.listMovies("user-2", new MediaPageQueryDto());
        assertTrue(page.getRecords().stream().noneMatch(MediaItemVo::getFavorited));
    }

    /**
     * VO favorited 填充：电影海报墙、剧详情（剧 + 季卡片）、季集列表（集）。
     */
    @Test
    void voFavoritedIsFilled() {
        // 电影墙
        MediaMovie movie = insertMovie("user-1", "dir-1", "测试电影", MediaMatchStatus.MATCHED.getCode());
        mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.MOVIE, movie.getId());
        MediaItemVo movieVo = mediaItemService.listMovies("user-1", new MediaPageQueryDto()).getRecords().stream()
                .filter(v -> v.getId().equals(movie.getId())).findFirst().orElseThrow();
        assertTrue(movieVo.getFavorited());

        // 剧详情：剧 + 季卡片；季集列表：集
        MediaSeries series = insertSeries("user-1", "dir-1", "测试剧");
        MediaSeason season1 = insertSeason(series, 1);
        MediaSeason season2 = insertSeason(series, 2);
        MediaEpisode episode = insertEpisode(series, season1, 1);
        mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.SERIES, series.getId());
        mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.SEASON, season1.getId());
        mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.EPISODE, episode.getId());

        MediaSeriesDetailVo detail = mediaItemService.getSeriesDetail(series.getId(), "user-1");
        assertTrue(detail.getFavorited());
        MediaSeriesSeasonVo s1 = detail.getSeasons().stream()
                .filter(s -> s.getSeasonId().equals(season1.getId())).findFirst().orElseThrow();
        MediaSeriesSeasonVo s2 = detail.getSeasons().stream()
                .filter(s -> s.getSeasonId().equals(season2.getId())).findFirst().orElseThrow();
        assertTrue(s1.getFavorited());
        assertFalse(s2.getFavorited());

        List<MediaItemVo> episodes = mediaItemService.listSeasonEpisodes(series.getId(), season1.getId(), "user-1");
        assertEquals(1, episodes.size());
        assertTrue(episodes.getFirst().getFavorited());
    }

    /**
     * 未匹配条目可收藏（不校验匹配状态）。
     */
    @Test
    void unmatchedItemsCanBeFavorited() {
        MediaMovie movie = insertMovie("user-1", "dir-1", "未识别电影", MediaMatchStatus.UNMATCHED.getCode());
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.MOVIE, movie.getId()));
        MediaSeries series = insertSeries("user-1", "dir-1", "未识别剧");
        series.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
        mediaSeriesMapper.updateById(series);
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.SERIES, series.getId()));
    }

    /**
     * 级联清理：删电影后收藏消失。
     */
    @Test
    void cascadeDeletesMovieFavorite() {
        MediaMovie movie = insertMovie("user-1", "dir-1", "测试电影", MediaMatchStatus.UNMATCHED.getCode());
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.MOVIE, movie.getId()));

        mediaMovieCascadeSupport.deleteMoviesCascade(List.of(movie.getId()));

        assertTrue(mediaFavoriteService.listFavoritedOwnerIds("user-1", MediaFavoriteOwnerType.MOVIE,
                List.of(movie.getId())).isEmpty());
        assertTrue(countFavorites("user-1", MediaFavoriteOwnerType.MOVIE, movie.getId()) == 0);
    }

    /**
     * 级联清理：删剧后剧/季/集收藏全部消失。
     */
    @Test
    void cascadeDeletesSeriesSeasonEpisodeFavorites() {
        MediaSeries series = insertSeries("user-1", "dir-1", "测试剧");
        MediaSeason season = insertSeason(series, 1);
        MediaEpisode episode = insertEpisode(series, season, 1);
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.SERIES, series.getId()));
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.SEASON, season.getId()));
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.EPISODE, episode.getId()));

        mediaTvCascadeSupport.deleteSeriesCascade(List.of(series.getId()));

        assertTrue(mediaFavoriteService.listFavoritedOwnerIds("user-1", MediaFavoriteOwnerType.SERIES,
                List.of(series.getId())).isEmpty());
        assertTrue(mediaFavoriteService.listFavoritedOwnerIds("user-1", MediaFavoriteOwnerType.SEASON,
                List.of(season.getId())).isEmpty());
        assertTrue(mediaFavoriteService.listFavoritedOwnerIds("user-1", MediaFavoriteOwnerType.EPISODE,
                List.of(episode.getId())).isEmpty());
    }

    /**
     * 级联清理：删其他条目后收藏消失。
     */
    @Test
    void cascadeDeletesOtherFavorite() {
        MediaOther other = insertOther("user-1", "dir-1");
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.OTHER, other.getId()));

        mediaOtherCascadeSupport.deleteOthersCascade(List.of(other.getId()));

        assertTrue(mediaFavoriteService.listFavoritedOwnerIds("user-1", MediaFavoriteOwnerType.OTHER,
                List.of(other.getId())).isEmpty());
    }

    /**
     * 级联清理：媒体库删除（deleteByDirectoryId）后收藏消失。
     */
    @Test
    void cascadeDeletesFavoritesByDirectoryId() {
        MediaMovie movie = insertMovie("user-1", "dir-1", "测试电影", MediaMatchStatus.UNMATCHED.getCode());
        MediaMovie otherDirMovie = insertMovie("user-1", "dir-2", "另一库电影", MediaMatchStatus.UNMATCHED.getCode());
        MediaOther other = insertOther("user-1", "dir-1");
        MediaSeries series = insertSeries("user-1", "dir-1", "测试剧");
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.MOVIE, movie.getId()));
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.MOVIE, otherDirMovie.getId()));
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.OTHER, other.getId()));
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.SERIES, series.getId()));

        mediaMovieCascadeSupport.deleteByDirectoryId("dir-1");
        mediaOtherCascadeSupport.deleteByDirectoryId("dir-1");
        mediaTvCascadeSupport.deleteByDirectoryId("dir-1");

        assertTrue(mediaFavoriteService.listFavoritedOwnerIds("user-1", MediaFavoriteOwnerType.MOVIE,
                List.of(movie.getId())).isEmpty());
        assertTrue(mediaFavoriteService.listFavoritedOwnerIds("user-1", MediaFavoriteOwnerType.MOVIE,
                List.of(otherDirMovie.getId())).contains(otherDirMovie.getId()));
        assertTrue(mediaFavoriteService.listFavoritedOwnerIds("user-1", MediaFavoriteOwnerType.OTHER,
                List.of(other.getId())).isEmpty());
        assertTrue(mediaFavoriteService.listFavoritedOwnerIds("user-1", MediaFavoriteOwnerType.SERIES,
                List.of(series.getId())).isEmpty());
    }

    /**
     * 级联清理：即时 reconcile 清理（deleteUnseenChildren）季/集后收藏消失。
     */
    @Test
    void cascadeDeletesFavoritesOnUnseenChildren() {
        MediaSeries series = insertSeries("user-1", "dir-1", "测试剧");
        MediaSeason season = insertSeason(series, 1);
        MediaEpisode episode = insertEpisode(series, season, 1);
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.SEASON, season.getId()));
        assertTrue(mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.EPISODE, episode.getId()));

        mediaTvCascadeSupport.deleteUnseenChildren(
                List.of(season), List.of(episode), List.of(),
                Set.of(), Set.of(), Set.of(), List.of());

        assertTrue(mediaFavoriteService.listFavoritedOwnerIds("user-1", MediaFavoriteOwnerType.SEASON,
                List.of(season.getId())).isEmpty());
        assertTrue(mediaFavoriteService.listFavoritedOwnerIds("user-1", MediaFavoriteOwnerType.EPISODE,
                List.of(episode.getId())).isEmpty());
    }

    /**
     * 目标实体不存在或不属于当前用户时抛业务异常。
     */
    @Test
    void toggleRejectsMissingOrForeignEntity() {
        assertThrows(BusinessException.class,
                () -> mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.MOVIE, "not-exists"));
        MediaMovie foreign = insertMovie("user-2", "dir-1", "他人电影", MediaMatchStatus.UNMATCHED.getCode());
        assertThrows(BusinessException.class,
                () -> mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.MOVIE, foreign.getId()));
    }

    private long countFavorites(String userId, MediaFavoriteOwnerType ownerType, String ownerId) {
        return mediaFavoriteMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MediaFavorite>()
                        .eq(MediaFavorite::getUserId, userId)
                        .eq(MediaFavorite::getOwnerType, ownerType.getCode())
                        .eq(MediaFavorite::getOwnerId, ownerId));
    }

    private MediaMovie insertMovie(String userId, String directoryId, String title, String matchStatus) {
        MediaMovie movie = new MediaMovie();
        movie.setUserId(userId);
        movie.setDirectoryId(directoryId);
        movie.setFolderNodeId(IdUtil.nextId());
        movie.setTitle(title);
        movie.setMatchStatus(matchStatus);
        movie.setMetadataComplete(false);
        mediaMovieMapper.insert(movie);
        return movie;
    }

    private MediaSeries insertSeries(String userId, String directoryId, String seriesName) {
        MediaSeries series = new MediaSeries();
        series.setUserId(userId);
        series.setDirectoryId(directoryId);
        series.setFolderNodeId(IdUtil.nextId());
        series.setSeriesName(seriesName);
        series.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
        mediaSeriesMapper.insert(series);
        return series;
    }

    private MediaSeason insertSeason(MediaSeries series, Integer seasonNo) {
        MediaSeason season = new MediaSeason();
        season.setSeriesId(series.getId());
        season.setFolderNodeId(IdUtil.nextId());
        season.setSeasonNo(seasonNo);
        mediaSeasonMapper.insert(season);
        return season;
    }

    private MediaEpisode insertEpisode(MediaSeries series, MediaSeason season, int episodeNo) {
        MediaEpisode episode = new MediaEpisode();
        episode.setSeriesId(series.getId());
        episode.setSeasonId(season.getId());
        episode.setEpisodeNo(episodeNo);
        mediaEpisodeMapper.insert(episode);
        return episode;
    }

    private MediaOther insertOther(String userId, String directoryId) {
        MediaOther other = new MediaOther();
        other.setUserId(userId);
        other.setDirectoryId(directoryId);
        other.setFileNodeId(IdUtil.nextId());
        other.setName("测试视频.mp4");
        mediaOtherMapper.insert(other);
        return other;
    }
}
