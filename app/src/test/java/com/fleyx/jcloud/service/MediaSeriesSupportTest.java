package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.service.support.MediaSeriesSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 电视剧/季层级维护组件测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaSeriesSupportTest {

    private static final String USER_ID = "testseries001";

    @Autowired
    private MediaSeriesSupport mediaSeriesSupport;

    @Autowired
    private MediaSeriesMapper mediaSeriesMapper;

    @Autowired
    private MediaSeasonMapper mediaSeasonMapper;

    @Autowired
    private MediaItemMapper mediaItemMapper;

    @Test
    void testGetOrCreateSeriesIdempotent() {
        MediaSeries first = mediaSeriesSupport.getOrCreateSeries(USER_ID, "庆余年", null);
        assertNotNull(first.getId());
        MediaSeries second = mediaSeriesSupport.getOrCreateSeries(USER_ID, "庆余年", null);
        assertEquals(first.getId(), second.getId());
        assertEquals(MediaMatchStatus.UNMATCHED.getCode(), first.getMatchStatus());
    }

    @Test
    void testGetOrCreateSeriesUsesCache() {
        Map<String, MediaSeries> cache = new HashMap<>();
        MediaSeries first = mediaSeriesSupport.getOrCreateSeries(USER_ID, "缓存剧", cache);
        cache.get("缓存剧").setSeriesName("被改过的缓存对象");
        MediaSeries second = mediaSeriesSupport.getOrCreateSeries(USER_ID, "缓存剧", cache);
        assertEquals(first.getId(), second.getId());
    }

    @Test
    void testGetOrCreateSeasonWithNullSeasonNo() {
        MediaSeries series = mediaSeriesSupport.getOrCreateSeries(USER_ID, "某剧", null);
        MediaSeason season = mediaSeriesSupport.getOrCreateSeason(series.getId(), null, null);
        assertNotNull(season.getId());
        assertNull(season.getSeasonNo());
        MediaSeason again = mediaSeriesSupport.getOrCreateSeason(series.getId(), null, null);
        assertEquals(season.getId(), again.getId());
    }

    @Test
    void testApplySeriesMatch() {
        MediaSeries series = mediaSeriesSupport.getOrCreateSeries(USER_ID, "匹配剧", null);
        mediaSeriesSupport.applySeriesMatch(series, "meta001");
        assertEquals("meta001", series.getMetadataId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(),
                mediaSeriesMapper.selectById(series.getId()).getMatchStatus());

        // 自动匹配为 unmatched 可覆盖
        mediaSeriesSupport.applySeriesMatch(series, null);
        assertEquals(MediaMatchStatus.UNMATCHED.getCode(),
                mediaSeriesMapper.selectById(series.getId()).getMatchStatus());
    }

    @Test
    void testApplySeriesMatchKeepsManual() {
        MediaSeries series = mediaSeriesSupport.getOrCreateSeries(USER_ID, "手动剧", null);
        mediaSeriesSupport.applySeriesMatch(series, "meta001");
        series.setMatchStatus(MediaMatchStatus.MANUAL.getCode());
        mediaSeriesSupport.applySeriesMatch(series, "meta999");
        // manual 不被覆盖
        assertEquals("meta001", mediaSeriesMapper.selectById(series.getId()).getMetadataId());
    }

    @Test
    void testRecalcMinFileLastModified() {
        MediaSeries series = mediaSeriesSupport.getOrCreateSeries(USER_ID, "重算剧", null);
        insertEpisode(series.getId(), "node-r1", 3000L);
        insertEpisode(series.getId(), "node-r2", 1000L);
        mediaSeriesSupport.recalcMinFileLastModified(Set.of(series.getId()));
        assertEquals(1000L, mediaSeriesMapper.selectById(series.getId()).getMinFileLastModified());
    }

    @Test
    void testCleanupOrphans() {
        MediaSeries kept = mediaSeriesSupport.getOrCreateSeries(USER_ID, "保留剧", null);
        MediaSeason keptSeason = mediaSeriesSupport.getOrCreateSeason(kept.getId(), 1, null);
        insertEpisode(kept.getId(), "node-k1", 1000L);

        MediaSeries orphan = mediaSeriesSupport.getOrCreateSeries(USER_ID, "孤儿剧", null);
        MediaSeason orphanSeason = mediaSeriesSupport.getOrCreateSeason(orphan.getId(), 1, null);

        mediaSeriesSupport.cleanupOrphans(USER_ID);

        assertNotNull(mediaSeriesMapper.selectById(kept.getId()));
        assertNotNull(mediaSeasonMapper.selectById(keptSeason.getId()));
        assertNull(mediaSeriesMapper.selectById(orphan.getId()));
        assertNull(mediaSeasonMapper.selectById(orphanSeason.getId()));
    }

    private void insertEpisode(String seriesId, String fileNodeId, long fileLastModified) {
        MediaItem item = new MediaItem();
        item.setUserId(USER_ID);
        item.setDirectoryId("dir-test-001");
        item.setFileNodeId(fileNodeId);
        item.setItemType("episode");
        item.setSeriesId(seriesId);
        item.setSeriesName("剧");
        item.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
        item.setFileLastModified(fileLastModified);
        item.setProgressMs(0L);
        mediaItemMapper.insert(item);
    }
}
