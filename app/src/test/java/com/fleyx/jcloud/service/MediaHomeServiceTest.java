package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.common.enums.MediaItemType;
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
import com.fleyx.jcloud.model.vo.MediaItemVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
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

    /**
     * 首页最新电影与最新剧集分别查询，按标题级实体聚合，保留无文件电影并组装元数据字段。
     */
    @Test
    void shouldReturnLatestMoviesAndSeriesAsIndependentTitleCards() {
        LocalDateTime older = playTime(10);
        LocalDateTime latest = playTime(20);
        MediaMovie movieWithFile = insertLatestMovie("有文件电影", older, true);
        insertMovieFile(movieWithFile, 200_000L);
        MediaMovie movieWithoutFile = insertLatestMovie("无文件电影", latest, false);
        attachMetadata(movieWithoutFile, "md00000000001", MediaMetadataOwnerType.MOVIE,
                "元数据电影", "2024-01-02", 8.5, "postermovie01");
        MediaSeries series = insertSeries("最新剧", latest);
        insertEpisode(series, 1, 1, 0L, 100_000L, null);
        insertEpisode(series, 1, 2, 0L, 100_000L, null);
        attachMetadata(series, "md00000000002", MediaMetadataOwnerType.SERIES,
                "元数据剧", "2023-03-04", 9.1, "posterseries1");
        insertSeries("无时间剧", null);
        insertOther(10L, null, latest);

        MediaHomeVo home = mediaHomeService.getHome(USER_ID);

        assertEquals(2, home.getLatestMovies().size());
        MediaItemVo latestMovie = home.getLatestMovies().get(0);
        assertEquals(movieWithoutFile.getId(), latestMovie.getId());
        assertEquals(MediaItemType.MOVIE.getCode(), latestMovie.getItemType());
        assertNull(latestMovie.getFileNodeId());
        assertEquals("md00000000001", latestMovie.getMetadataId());
        assertEquals("元数据电影", latestMovie.getTitle());
        assertEquals("2024-01-02", latestMovie.getReleaseDate());
        assertEquals(8.5, latestMovie.getVoteAverage());
        assertEquals("/jcloud/api/media/metadata/md00000000001/poster", latestMovie.getPosterUrl());
        assertEquals(latest, latestMovie.getAddedTime());
        assertEquals(movieWithFile.getId(), home.getLatestMovies().get(1).getId());

        assertEquals(1, home.getLatestSeries().size());
        MediaItemVo latestSeries = home.getLatestSeries().getFirst();
        assertEquals(MediaItemType.SERIES.getCode(), latestSeries.getItemType());
        assertEquals(series.getId(), latestSeries.getId());
        assertEquals(series.getId(), latestSeries.getSeriesId());
        assertNull(latestSeries.getFileNodeId());
        assertEquals("md00000000002", latestSeries.getMetadataId());
        assertEquals("元数据剧", latestSeries.getTitle());
        assertEquals("2023-03-04", latestSeries.getReleaseDate());
        assertEquals(9.1, latestSeries.getVoteAverage());
        assertEquals("/jcloud/api/media/metadata/md00000000002/poster", latestSeries.getPosterUrl());
        assertEquals(latest, latestSeries.getAddedTime());
    }

    /**
     * 最新电影与最新剧集各自最多返回 16 条，并在时间相同时按实体 ID 倒序。
     */
    @Test
    void shouldLimitLatestMoviesAndSeriesWithStableEntityOrdering() {
        LocalDateTime addedTime = playTime(30);
        for (int i = 0; i < 18; i++) {
            insertLatestMovie(String.format("mv%011d", i), "电影" + i, addedTime, false);
            insertSeries(String.format("sr%011d", i), "剧" + i, addedTime);
        }

        MediaHomeVo home = mediaHomeService.getHome(USER_ID);

        assertEquals(16, home.getLatestMovies().size());
        assertEquals("mv00000000017", home.getLatestMovies().getFirst().getId());
        assertEquals("mv00000000002", home.getLatestMovies().get(15).getId());
        assertEquals(16, home.getLatestSeries().size());
        assertEquals("sr00000000017", home.getLatestSeries().getFirst().getId());
        assertEquals("sr00000000002", home.getLatestSeries().get(15).getId());
    }

    /**
     * 没有实体入库时间时，两个最新列表均为空。
     */
    @Test
    void shouldReturnEmptyLatestListsWhenNoAddedTimeExists() {
        insertMovie(0L, null, null);
        insertSeries("无最新时间剧");

        MediaHomeVo home = mediaHomeService.getHome(USER_ID);

        assertTrue(home.getLatestMovies().isEmpty());
        assertTrue(home.getLatestSeries().isEmpty());
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
        return insertSeries(null, seriesName, null);
    }

    private MediaSeries insertSeries(String seriesName, LocalDateTime latestAddedTime) {
        return insertSeries(null, seriesName, latestAddedTime);
    }

    private MediaSeries insertSeries(String id, String seriesName, LocalDateTime latestAddedTime) {
        MediaSeries series = new MediaSeries();
        series.setId(id);
        series.setUserId(USER_ID);
        series.setDirectoryId("dir-home-001");
        series.setFolderNodeId(nextNodeId());
        series.setSeriesName(seriesName);
        series.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
        series.setMetadataComplete(false);
        series.setLatestAddedTime(latestAddedTime);
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
        return insertMovie(null, "测试电影", progressMs, durationMs, lastPlayTime, null, true);
    }

    private MediaMovie insertLatestMovie(String title, LocalDateTime addedTime, boolean withFile) {
        return insertMovie(null, title, 0L, null, null, addedTime, withFile);
    }

    private MediaMovie insertLatestMovie(String id, String title, LocalDateTime addedTime, boolean withFile) {
        return insertMovie(id, title, 0L, null, null, addedTime, withFile);
    }

    private MediaMovie insertMovie(String id, String title, Long progressMs, Long durationMs,
                                   LocalDateTime lastPlayTime, LocalDateTime addedTime, boolean withFile) {
        MediaMovie movie = new MediaMovie();
        movie.setId(id);
        movie.setUserId(USER_ID);
        movie.setDirectoryId("dir-home-001");
        movie.setFolderNodeId(nextNodeId());
        movie.setTitle(title);
        movie.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
        movie.setMetadataComplete(false);
        movie.setProgressMs(progressMs);
        movie.setLastPlayTime(lastPlayTime);
        movie.setAddedTime(addedTime);
        mediaMovieMapper.insert(movie);

        if (withFile) {
            insertMovieFile(movie, durationMs);
        }
        return movie;
    }

    private void insertMovieFile(MediaMovie movie, Long durationMs) {
        MediaMovieFile file = new MediaMovieFile();
        file.setMovieId(movie.getId());
        file.setFileNodeId(nextNodeId());
        file.setDurationMs(durationMs);
        mediaMovieFileMapper.insert(file);
    }

    private void attachMetadata(MediaMovie movie, String metadataId, MediaMetadataOwnerType ownerType,
                                String title, String releaseDate, Double voteAverage, String posterFileNodeId) {
        attachMetadata(metadataId, ownerType, movie.getId(), title, releaseDate, voteAverage, posterFileNodeId);
        movie.setMetadataId(metadataId);
        mediaMovieMapper.updateById(movie);
    }

    private void attachMetadata(MediaSeries series, String metadataId, MediaMetadataOwnerType ownerType,
                                String title, String releaseDate, Double voteAverage, String posterFileNodeId) {
        attachMetadata(metadataId, ownerType, series.getId(), title, releaseDate, voteAverage, posterFileNodeId);
        series.setMetadataId(metadataId);
        mediaSeriesMapper.updateById(series);
    }

    private void attachMetadata(String metadataId, MediaMetadataOwnerType ownerType, String ownerId,
                                String title, String releaseDate, Double voteAverage, String posterFileNodeId) {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setId(metadataId);
        metadata.setUserId(USER_ID);
        metadata.setOwnerType(ownerType.getCode());
        metadata.setOwnerId(ownerId);
        metadata.setSource("tmdb");
        metadata.setPersistStatus("persisted");
        metadata.setTitle(title);
        metadata.setReleaseDate(releaseDate);
        metadata.setVoteAverage(voteAverage);
        metadata.setPosterFileNodeId(posterFileNodeId);
        mediaMetadataMapper.insert(metadata);
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
