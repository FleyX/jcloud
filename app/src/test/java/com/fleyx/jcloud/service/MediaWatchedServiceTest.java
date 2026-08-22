package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.MediaProgressUpdateDto;
import com.fleyx.jcloud.model.dto.MediaWatchedUpdateDto;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.service.support.MediaHomeContinueWatchingSupport;
import com.fleyx.jcloud.service.support.MediaHomeItemSupport;
import com.fleyx.jcloud.service.support.MediaHomeNextUpSupport;
import com.fleyx.jcloud.util.IdUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已观看标记服务测试（工单 01：电影/集/其他的手动标记与进度驱动自动标记）。
 * <p>
 * 覆盖验收标准：手动标记已观看清零进度、标记未观看不动进度、进度达看完阈值自动置位并清零、
 * 重看不复活、继续观看排除已观看、集列表 VO 带 watched、下一集跳过已观看。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaWatchedServiceTest {

    private static final String USER_ID = "watcher000001";

    @Autowired
    private MediaItemService mediaItemService;
    @Autowired
    private MediaHomeContinueWatchingSupport continueWatchingSupport;
    @Autowired
    private MediaHomeNextUpSupport nextUpSupport;
    @Autowired
    private MediaMovieMapper mediaMovieMapper;
    @Autowired
    private MediaMovieFileMapper mediaMovieFileMapper;
    @Autowired
    private MediaEpisodeMapper mediaEpisodeMapper;
    @Autowired
    private MediaEpisodeFileMapper mediaEpisodeFileMapper;
    @Autowired
    private MediaSeasonMapper mediaSeasonMapper;
    @Autowired
    private MediaSeriesMapper mediaSeriesMapper;
    @Autowired
    private MediaOtherMapper mediaOtherMapper;

    /**
     * 标记已观看：watched 置 true 且进度清零；取消标记：仅 watched 变化，进度不动。
     */
    @Test
    void markWatchedClearsProgressAndUnmarkKeepsProgress() {
        MediaMovie movie = insertMovie(50_000L, false, null);

        mediaItemService.updateWatched(movie.getId(), dto(true), USER_ID);
        MediaMovie marked = mediaMovieMapper.selectById(movie.getId());
        assertTrue(Boolean.TRUE.equals(marked.getWatched()));
        assertEquals(0L, marked.getProgressMs().longValue());

        mediaItemService.updateWatched(movie.getId(), dto(false), USER_ID);
        MediaMovie unmarked = mediaMovieMapper.selectById(movie.getId());
        assertFalse(Boolean.TRUE.equals(unmarked.getWatched()));
        assertEquals(0L, unmarked.getProgressMs().longValue());
    }

    /**
     * 进度上报未达看完阈值（94%）：不动 watched，进度照写。
     */
    @Test
    void progressBelowThresholdDoesNotMark() {
        MediaOther other = insertOther(0L, false, 100_000L);

        mediaItemService.updateProgress(other.getId(), progress(94_000L), USER_ID);
        MediaOther row = mediaOtherMapper.selectById(other.getId());
        assertFalse(Boolean.TRUE.equals(row.getWatched()));
        assertEquals(94_000L, row.getProgressMs().longValue());
    }

    /**
     * 进度上报达看完阈值（95%）：自动置 watched 并清零进度。
     */
    @Test
    void progressAtThresholdMarksAndClears() {
        MediaOther other = insertOther(0L, false, 100_000L);

        mediaItemService.updateProgress(other.getId(), progress(95_000L), USER_ID);
        MediaOther row = mediaOtherMapper.selectById(other.getId());
        assertTrue(Boolean.TRUE.equals(row.getWatched()));
        assertEquals(0L, row.getProgressMs().longValue());
    }

    /**
     * 重看场景：已 watched 的行再上报半程不复活（watched 保持、进度照写）；
     * 再次达阈值则重新清零。
     */
    @Test
    void rewatchDoesNotUnmarkAndReachThresholdClearsAgain() {
        MediaMovie movie = insertMovie(0L, true, 100_000L);

        mediaItemService.updateProgress(movie.getId(), progress(50_000L), USER_ID);
        MediaMovie half = mediaMovieMapper.selectById(movie.getId());
        assertTrue(Boolean.TRUE.equals(half.getWatched()));
        assertEquals(50_000L, half.getProgressMs().longValue());

        mediaItemService.updateProgress(movie.getId(), progress(96_000L), USER_ID);
        MediaMovie done = mediaMovieMapper.selectById(movie.getId());
        assertTrue(Boolean.TRUE.equals(done.getWatched()));
        assertEquals(0L, done.getProgressMs().longValue());
    }

    /**
     * 继续观看排除已 watched 条目；未看完（watched=false）条目继续收录。
     */
    @Test
    void continueWatchingExcludesWatched() {
        MediaMovie inProgress = insertMovie(50_000L, false, 100_000L);
        insertMovie(20_000L, true, 100_000L);

        List<MediaHomeItemSupport.HomeItem> items =
                continueWatchingSupport.listContinueWatching(USER_ID, 16);

        assertTrue(items.stream().anyMatch(i -> i.id().equals(inProgress.getId())));
        assertTrue(items.stream().noneMatch(i -> Boolean.TRUE.equals(i.watched())));
    }

    /**
     * 集列表 VO 带 watched，供前端「下一集」以标记为准定位。
     */
    @Test
    void episodeListVoCarriesWatched() {
        MediaSeries series = insertSeries();
        MediaSeason season = insertSeason(series, 1);
        MediaEpisode watched = insertEpisode(series, season, 1, true, null);
        MediaEpisode unwatched = insertEpisode(series, season, 2, false, null);

        List<MediaItemVo> vos = mediaItemService.listEpisodes(series.getId(), USER_ID);
        MediaItemVo w = vos.stream().filter(v -> v.getId().equals(watched.getId())).findFirst().orElseThrow();
        MediaItemVo u = vos.stream().filter(v -> v.getId().equals(unwatched.getId())).findFirst().orElseThrow();
        assertTrue(w.getWatched());
        assertFalse(u.getWatched());
    }

    /**
     * 下一集定位跳过已观看的集；全部看完的剧不再产出 nextUp。
     */
    @Test
    void nextUpSkipsWatchedAndExcludesAllWatchedSeries() {
        MediaSeries seriesA = insertSeries();
        MediaSeason seasonA = insertSeason(seriesA, 1);
        insertEpisode(seriesA, seasonA, 1, true, playTime(10));
        MediaEpisode a2 = insertEpisode(seriesA, seasonA, 2, false, playTime(20));
        insertEpisode(seriesA, seasonA, 3, false, null);

        MediaSeries seriesB = insertSeries();
        MediaSeason seasonB = insertSeason(seriesB, 1);
        insertEpisode(seriesB, seasonB, 1, true, playTime(30));

        List<MediaHomeItemSupport.HomeItem> nextUp = nextUpSupport.listNextUp(USER_ID, 16);

        assertTrue(nextUp.stream().anyMatch(i -> i.id().equals(a2.getId())));
        assertTrue(nextUp.stream().noneMatch(i -> i.seriesId().equals(seriesB.getId())));
    }

    private MediaWatchedUpdateDto dto(boolean watched) {
        MediaWatchedUpdateDto dto = new MediaWatchedUpdateDto();
        dto.setWatched(watched);
        return dto;
    }

    private MediaProgressUpdateDto progress(long progressMs) {
        MediaProgressUpdateDto dto = new MediaProgressUpdateDto();
        dto.setProgressMs(progressMs);
        return dto;
    }

    private MediaMovie insertMovie(Long progressMs, boolean watched, Long durationMs) {
        MediaMovie movie = new MediaMovie();
        movie.setUserId(USER_ID);
        movie.setDirectoryId("dir-watch-001");
        movie.setFolderNodeId(IdUtil.nextId());
        movie.setTitle("测试电影");
        movie.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
        movie.setMetadataComplete(false);
        movie.setProgressMs(progressMs);
        movie.setWatched(watched);
        mediaMovieMapper.insert(movie);
        if (durationMs != null) {
            MediaMovieFile file = new MediaMovieFile();
            file.setMovieId(movie.getId());
            file.setFileNodeId(IdUtil.nextId());
            file.setDurationMs(durationMs);
            mediaMovieFileMapper.insert(file);
        }
        return movie;
    }

    private MediaOther insertOther(Long progressMs, boolean watched, Long durationMs) {
        MediaOther other = new MediaOther();
        other.setUserId(USER_ID);
        other.setDirectoryId("dir-watch-001");
        other.setFileNodeId(IdUtil.nextId());
        other.setName("测试素材");
        other.setProgressMs(progressMs);
        other.setWatched(watched);
        other.setDurationMs(durationMs);
        mediaOtherMapper.insert(other);
        return other;
    }

    private MediaSeries insertSeries() {
        MediaSeries series = new MediaSeries();
        series.setUserId(USER_ID);
        series.setDirectoryId("dir-watch-001");
        series.setFolderNodeId(IdUtil.nextId());
        series.setSeriesName("测试剧");
        series.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
        series.setMetadataComplete(false);
        mediaSeriesMapper.insert(series);
        return series;
    }

    private MediaSeason insertSeason(MediaSeries series, int seasonNo) {
        MediaSeason season = new MediaSeason();
        season.setSeriesId(series.getId());
        season.setFolderNodeId(IdUtil.nextId());
        season.setSeasonNo(seasonNo);
        mediaSeasonMapper.insert(season);
        return season;
    }

    private MediaEpisode insertEpisode(MediaSeries series, MediaSeason season, int episodeNo,
                                       boolean watched, LocalDateTime lastPlayTime) {
        MediaEpisode episode = new MediaEpisode();
        episode.setSeriesId(series.getId());
        episode.setSeasonId(season.getId());
        episode.setEpisodeNo(episodeNo);
        episode.setWatched(watched);
        episode.setLastPlayTime(lastPlayTime);
        mediaEpisodeMapper.insert(episode);
        MediaEpisodeFile file = new MediaEpisodeFile();
        file.setEpisodeId(episode.getId());
        file.setFileNodeId(IdUtil.nextId());
        file.setDurationMs(100_000L);
        mediaEpisodeFileMapper.insert(file);
        return episode;
    }

    private LocalDateTime playTime(int minutes) {
        return LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(minutes);
    }
}
