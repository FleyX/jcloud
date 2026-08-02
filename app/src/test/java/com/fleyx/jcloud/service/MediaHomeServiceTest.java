package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.MediaDirectoryVo;
import com.fleyx.jcloud.model.vo.MediaHomeVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 影视首页聚合服务测试（issue #19 起切新表：电影/集/其他标题级行）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaHomeServiceTest {

    private static final String USER_ID = "homeuser00001";

    private final AtomicLong fileNodeSeq = new AtomicLong();

    @Autowired
    private MediaHomeService mediaHomeService;

    @Autowired
    private MediaDirectoryMapper mediaDirectoryMapper;

    @Autowired
    private MediaSeriesMapper mediaSeriesMapper;

    @Autowired
    private MediaSeasonMapper mediaSeasonMapper;

    @Autowired
    private MediaEpisodeMapper mediaEpisodeMapper;

    @Autowired
    private MediaEpisodeFileMapper mediaEpisodeFileMapper;

    @Autowired
    private MediaMovieMapper mediaMovieMapper;

    @Autowired
    private MediaMovieFileMapper mediaMovieFileMapper;

    @Autowired
    private MediaOtherMapper mediaOtherMapper;

    @Autowired
    private MediaMetadataMapper mediaMetadataMapper;

    /**
     * 继续观看收录规则：在播条目收录；看完（≥95%）与无进度排除；
     * 时长为空无法计算阈值时有进度即收录；按最近播放时间倒序。
     */
    @Test
    void shouldCollectContinueWatchingByRules() {
        MediaMovie inProgress = insertMovie(50_000L, 100_000L, playTime(5));
        insertMovie(95_000L, 100_000L, playTime(4));
        MediaOther noDuration = insertOther(10L, null, playTime(3));
        insertMovie(0L, 100_000L, null);

        MediaHomeVo home = mediaHomeService.getHome(USER_ID);

        assertEquals(2, home.getContinueWatching().size());
        assertEquals(inProgress.getId(), home.getContinueWatching().get(0).getId());
        assertEquals(noDuration.getId(), home.getContinueWatching().get(1).getId());
    }

    /**
     * 继续观看最多返回 16 条（最近播放优先）。
     */
    @Test
    void shouldLimitContinueWatching() {
        MediaMovie latest = null;
        for (int i = 0; i < 18; i++) {
            latest = insertMovie(1_000L + i, null, playTime(i));
        }

        MediaHomeVo home = mediaHomeService.getHome(USER_ID);

        assertEquals(16, home.getContinueWatching().size());
        assertEquals(latest.getId(), home.getContinueWatching().get(0).getId());
    }

    /**
     * 接下来：取每部有观看记录的剧的第一集未观看集；在播集排除（归入继续观看）；
     * 无观看记录的剧不收录；按剧最近播放时间倒序。
     */
    @Test
    void shouldPickNextUpAndExcludeInProgress() {
        MediaSeries seriesA = insertSeries("剧A");
        insertEpisode(seriesA, 1, 1, 95_000L, 100_000L, playTime(10));
        MediaEpisode a2 = insertEpisode(seriesA, 1, 2, 0L, 100_000L, null);
        MediaSeries seriesB = insertSeries("剧B");
        MediaEpisode b1 = insertEpisode(seriesB, 1, 1, 10_000L, 100_000L, playTime(20));
        MediaEpisode b2 = insertEpisode(seriesB, 1, 2, 0L, 100_000L, null);
        MediaSeries seriesC = insertSeries("剧C");
        insertEpisode(seriesC, 1, 1, 0L, 100_000L, null);

        MediaHomeVo home = mediaHomeService.getHome(USER_ID);

        assertEquals(2, home.getNextUp().size());
        assertEquals(b2.getId(), home.getNextUp().get(0).getId());
        assertEquals("剧B", home.getNextUp().get(0).getSeriesName());
        assertEquals(seriesB.getId(), home.getNextUp().get(0).getSeriesId());
        assertEquals(1, home.getNextUp().get(0).getSeasonNo());
        assertEquals(a2.getId(), home.getNextUp().get(1).getId());
        // 在播集归入继续观看
        assertEquals(1, home.getContinueWatching().size());
        assertEquals(b1.getId(), home.getContinueWatching().get(0).getId());
    }

    /**
     * 接下来：全部看完的剧不收录。
     */
    @Test
    void shouldSkipSeriesWhenAllEpisodesFinished() {
        MediaSeries series = insertSeries("看完的剧");
        insertEpisode(series, 1, 1, 96_000L, 100_000L, playTime(10));

        MediaHomeVo home = mediaHomeService.getHome(USER_ID);

        assertTrue(home.getNextUp().isEmpty());
    }

    /**
     * 媒体库封面：电影/电视库取库内最新有海报条目的元数据海报（新表，issue #21）；
     * 其他库用最新 other 行（新表）的预览缩略图兜底；空库为空。
     */
    @Test
    void shouldResolveLibraryCovers() {
        MediaDirectory movieDir = insertDirectory("电影库", "movie");
        MediaMovie movie = new MediaMovie();
        movie.setUserId(USER_ID);
        movie.setDirectoryId(movieDir.getId());
        movie.setFolderNodeId(nextNodeId());
        movie.setTitle("测试电影");
        movie.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
        movie.setMetadataComplete(false);
        mediaMovieMapper.insert(movie);
        MediaMetadata metadata = new MediaMetadata();
        metadata.setId("metaposter001");
        metadata.setUserId(USER_ID);
        metadata.setOwnerType(MediaMetadataOwnerType.MOVIE.getCode());
        metadata.setOwnerId(movie.getId());
        metadata.setSource("tmdb");
        metadata.setPersistStatus("persisted");
        metadata.setTitle("电影");
        metadata.setPosterFileNodeId("fnposter00001");
        mediaMetadataMapper.insert(metadata);
        movie.setMetadataId(metadata.getId());
        mediaMovieMapper.updateById(movie);

        MediaDirectory otherDir = insertDirectory("其他库", "other");
        insertOtherIntoDir(otherDir.getId(), 0L, null, null);

        insertDirectory("空库", "movie");

        MediaHomeVo home = mediaHomeService.getHome(USER_ID);
        Map<String, MediaDirectoryVo> byName = home.getLibraries().stream()
                .collect(Collectors.toMap(MediaDirectoryVo::getName, Function.identity()));

        assertEquals("/jcloud/api/media/metadata/metaposter001/poster", byName.get("电影库").getCoverPosterUrl());
        MediaOther latest = mediaOtherMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MediaOther>()
                .eq(MediaOther::getDirectoryId, otherDir.getId()));
        assertEquals("/jcloud/api/files/" + latest.getFileNodeId() + "/preview?type=poster",
                byName.get("其他库").getCoverPosterUrl());
        assertNull(byName.get("空库").getCoverPosterUrl());
    }

    private MediaDirectory insertDirectory(String name, String mediaType) {
        MediaDirectory directory = new MediaDirectory();
        directory.setUserId(USER_ID);
        directory.setName(name);
        directory.setMediaType(mediaType);
        mediaDirectoryMapper.insert(directory);
        return directory;
    }

    private MediaSeries insertSeries(String seriesName) {
        MediaSeries series = new MediaSeries();
        series.setUserId(USER_ID);
        series.setDirectoryId("dir-home-001");
        series.setFolderNodeId(nextNodeId());
        series.setSeriesName(seriesName);
        series.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
        series.setMetadataComplete(false);
        mediaSeriesMapper.insert(series);
        return series;
    }

    private MediaEpisode insertEpisode(MediaSeries series, int seasonNo, int episodeNo,
                                       Long progressMs, Long durationMs, LocalDateTime lastPlayTime) {
        MediaSeason season = new MediaSeason();
        season.setSeriesId(series.getId());
        season.setFolderNodeId(nextNodeId());
        season.setSeasonNo(seasonNo);
        mediaSeasonMapper.insert(season);

        MediaEpisode episode = new MediaEpisode();
        episode.setSeriesId(series.getId());
        episode.setSeasonId(season.getId());
        episode.setEpisodeNo(episodeNo);
        episode.setProgressMs(progressMs);
        episode.setLastPlayTime(lastPlayTime);
        mediaEpisodeMapper.insert(episode);

        MediaEpisodeFile file = new MediaEpisodeFile();
        file.setEpisodeId(episode.getId());
        file.setFileNodeId(nextNodeId());
        file.setDurationMs(durationMs);
        mediaEpisodeFileMapper.insert(file);
        return episode;
    }

    private MediaMovie insertMovie(Long progressMs, Long durationMs, LocalDateTime lastPlayTime) {
        MediaMovie movie = new MediaMovie();
        movie.setUserId(USER_ID);
        movie.setDirectoryId("dir-home-001");
        movie.setFolderNodeId(nextNodeId());
        movie.setTitle("测试电影");
        movie.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
        movie.setMetadataComplete(false);
        movie.setProgressMs(progressMs);
        movie.setLastPlayTime(lastPlayTime);
        mediaMovieMapper.insert(movie);

        MediaMovieFile file = new MediaMovieFile();
        file.setMovieId(movie.getId());
        file.setFileNodeId(nextNodeId());
        file.setDurationMs(durationMs);
        mediaMovieFileMapper.insert(file);
        return movie;
    }

    private MediaOther insertOther(Long progressMs, Long durationMs, LocalDateTime lastPlayTime) {
        MediaOther other = new MediaOther();
        other.setUserId(USER_ID);
        other.setDirectoryId("dir-home-001");
        other.setFileNodeId(nextNodeId());
        other.setName("测试素材");
        other.setProgressMs(progressMs);
        other.setLastPlayTime(lastPlayTime);
        other.setDurationMs(durationMs);
        mediaOtherMapper.insert(other);
        return other;
    }

    private MediaOther insertOtherIntoDir(String directoryId, Long progressMs, Long durationMs,
                                          LocalDateTime lastPlayTime) {
        MediaOther other = new MediaOther();
        other.setUserId(USER_ID);
        other.setDirectoryId(directoryId);
        other.setFileNodeId(nextNodeId());
        other.setName("测试素材");
        other.setProgressMs(progressMs);
        other.setLastPlayTime(lastPlayTime);
        other.setDurationMs(durationMs);
        mediaOtherMapper.insert(other);
        return other;
    }

    private String nextNodeId() {
        return String.format("fn%011d", fileNodeSeq.incrementAndGet());
    }

    private LocalDateTime playTime(int minutes) {
        return LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(minutes);
    }
}
