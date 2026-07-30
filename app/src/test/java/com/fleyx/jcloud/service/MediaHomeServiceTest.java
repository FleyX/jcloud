package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaMetadata;
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
 * 影视首页聚合服务测试。
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
    private MediaItemMapper mediaItemMapper;

    @Autowired
    private MediaSeriesMapper mediaSeriesMapper;

    @Autowired
    private MediaMetadataMapper mediaMetadataMapper;

    /**
     * 继续观看收录规则：在播条目收录；看完（≥95%）与无进度排除；
     * 时长为空无法计算阈值时有进度即收录；按最近播放时间倒序。
     */
    @Test
    void shouldCollectContinueWatchingByRules() {
        MediaItem inProgress = insertItem(MediaItemType.MOVIE.getCode(), 50_000L, 100_000L, playTime(5));
        insertItem(MediaItemType.MOVIE.getCode(), 95_000L, 100_000L, playTime(4));
        MediaItem noDuration = insertItem(MediaItemType.OTHER.getCode(), 10L, null, playTime(3));
        insertItem(MediaItemType.MOVIE.getCode(), 0L, 100_000L, null);

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
        MediaItem latest = null;
        for (int i = 0; i < 18; i++) {
            latest = insertItem(MediaItemType.MOVIE.getCode(), 1_000L + i, null, playTime(i));
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
        MediaItem a2 = insertEpisode(seriesA, 1, 2, 0L, 100_000L, null);
        MediaSeries seriesB = insertSeries("剧B");
        MediaItem b1 = insertEpisode(seriesB, 1, 1, 10_000L, 100_000L, playTime(20));
        MediaItem b2 = insertEpisode(seriesB, 1, 2, 0L, 100_000L, null);
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
     * 媒体库封面：电影/电视库取最新有海报条目的元数据海报；其他库用最新条目预览缩略图；空库为空。
     */
    @Test
    void shouldResolveLibraryCovers() {
        MediaDirectory movieDir = insertDirectory("电影库", "movie");
        MediaMetadata metadata = new MediaMetadata();
        metadata.setId("metaposter001");
        metadata.setMediaType("movie");
        metadata.setTitle("电影");
        metadata.setPosterPath("poster/a.jpg");
        mediaMetadataMapper.insert(metadata);
        MediaItem movieItem = insertItem(MediaItemType.MOVIE.getCode(), 0L, null, null);
        movieItem.setDirectoryId(movieDir.getId());
        movieItem.setMetadataId(metadata.getId());
        movieItem.setFileLastModified(1000L);
        mediaItemMapper.updateById(movieItem);

        MediaDirectory otherDir = insertDirectory("其他库", "other");
        MediaItem otherItem = insertItem(MediaItemType.OTHER.getCode(), 0L, null, null);
        otherItem.setDirectoryId(otherDir.getId());
        otherItem.setFileLastModified(2000L);
        mediaItemMapper.updateById(otherItem);

        insertDirectory("空库", "movie");

        MediaHomeVo home = mediaHomeService.getHome(USER_ID);
        Map<String, MediaDirectoryVo> byName = home.getLibraries().stream()
                .collect(Collectors.toMap(MediaDirectoryVo::getName, Function.identity()));

        assertEquals("/jcloud/api/media/metadata/metaposter001/poster", byName.get("电影库").getCoverPosterUrl());
        assertEquals("/jcloud/api/files/" + otherItem.getFileNodeId() + "/preview?type=poster",
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
        series.setSeriesName(seriesName);
        series.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
        mediaSeriesMapper.insert(series);
        return series;
    }

    private MediaItem insertEpisode(MediaSeries series, int seasonNo, int episodeNo,
                                    Long progressMs, Long durationMs, LocalDateTime lastPlayTime) {
        MediaItem item = insertItem(MediaItemType.EPISODE.getCode(), progressMs, durationMs, lastPlayTime);
        item.setSeriesId(series.getId());
        item.setSeriesName(series.getSeriesName());
        item.setSeasonNo(seasonNo);
        item.setEpisodeNo(episodeNo);
        mediaItemMapper.updateById(item);
        return item;
    }

    private MediaItem insertItem(String itemType, Long progressMs, Long durationMs, LocalDateTime lastPlayTime) {
        MediaItem item = new MediaItem();
        item.setUserId(USER_ID);
        item.setDirectoryId("dir-home-001");
        item.setFileNodeId(String.format("fn%011d", fileNodeSeq.incrementAndGet()));
        item.setItemType(itemType);
        item.setProgressMs(progressMs);
        item.setDurationMs(durationMs);
        item.setLastPlayTime(lastPlayTime);
        item.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
        mediaItemMapper.insert(item);
        return item;
    }

    private LocalDateTime playTime(int minutes) {
        return LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(minutes);
    }
}
