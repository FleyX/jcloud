package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.util.IdUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三级联动 reconcile 挂接点直调测试（工单 02）。
 * <p>
 * 直接调用 {@link MediaTvReconcileSupport#createEpisode} 与
 * {@link MediaTvCascadeSupport#deleteUnseenChildren}，证明两处挂接存在：
 * 新增未观看集 → 父级标记自动清除；集删除 → 父级标记重算。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaWatchedLinkageReconcileSupportTest {

    private static final String USER_ID = "watcher000003";

    @Autowired
    private MediaSeriesMapper mediaSeriesMapper;
    @Autowired
    private MediaSeasonMapper mediaSeasonMapper;
    @Autowired
    private MediaEpisodeMapper mediaEpisodeMapper;
    @Autowired
    private MediaEpisodeFileMapper mediaEpisodeFileMapper;
    @Autowired
    private MediaTvReconcileSupport mediaTvReconcileSupport;
    @Autowired
    private MediaTvCascadeSupport mediaTvCascadeSupport;
    @Autowired
    private MediaWatchedLinkageSupport mediaWatchedLinkageSupport;

    /**
     * createEpisode 挂接：整剧已看完（父级 watched=true）后新增未观看集 → 季/剧标记自动清除（追更回到未看完）。
     */
    @Test
    void createEpisodeNewUnwatchedClearsParents() {
        MediaSeries series = insertSeries();
        MediaSeason season = insertSeason(series, 1);
        MediaEpisode e1 = insertEpisode(series, season, 1, true);
        MediaEpisode e2 = insertEpisode(series, season, 2, true);
        mediaWatchedLinkageSupport.recomputeParents(season.getId(), series.getId());
        assertTrue(Boolean.TRUE.equals(mediaSeasonMapper.selectById(season.getId()).getWatched()));
        assertTrue(Boolean.TRUE.equals(mediaSeriesMapper.selectById(series.getId()).getWatched()));

        List<MediaEpisode> existingEpisodes = new ArrayList<>(List.of(e1, e2));
        Map<String, MediaEpisode> episodeById = new HashMap<>();
        episodeById.put(e1.getId(), e1);
        episodeById.put(e2.getId(), e2);
        MediaEpisode created = mediaTvReconcileSupport.createEpisode(
                series, season, 3, existingEpisodes, episodeById);

        assertFalse(Boolean.TRUE.equals(created.getWatched()));
        assertFalse(Boolean.TRUE.equals(mediaSeasonMapper.selectById(season.getId()).getWatched()));
        assertFalse(Boolean.TRUE.equals(mediaSeriesMapper.selectById(series.getId()).getWatched()));
    }

    /**
     * deleteUnseenChildren 挂接：整剧已看完且某集被物理删除（unseen）→ 删集后父级标记重算清除。
     */
    @Test
    void deleteUnseenChildrenRecomputesParentsOnEpisodeDelete() {
        MediaSeries series = insertSeries();
        MediaSeason season = insertSeason(series, 1);
        MediaEpisode e1 = insertEpisode(series, season, 1, true);
        MediaEpisodeFile f1 = mediaEpisodeFileMapper.selectOne(new LambdaQueryWrapper<MediaEpisodeFile>()
                .eq(MediaEpisodeFile::getEpisodeId, e1.getId()));
        mediaWatchedLinkageSupport.recomputeParents(season.getId(), series.getId());
        assertTrue(Boolean.TRUE.equals(mediaSeasonMapper.selectById(season.getId()).getWatched()));

        // 季已被亲眼确认（seen），集与集文件 unseen → 删集后仅重算父级，季行保留
        mediaTvCascadeSupport.deleteUnseenChildren(List.of(season), List.of(e1), List.of(f1),
                Set.of(season.getId()), Set.of(), Set.of(), List.of());

        assertNull(mediaEpisodeMapper.selectById(e1.getId()));
        // 季内已无集 → 季/剧标记均清除
        assertFalse(Boolean.TRUE.equals(mediaSeasonMapper.selectById(season.getId()).getWatched()));
        assertFalse(Boolean.TRUE.equals(mediaSeriesMapper.selectById(series.getId()).getWatched()));
    }

    private MediaSeries insertSeries() {
        MediaSeries series = new MediaSeries();
        series.setUserId(USER_ID);
        series.setDirectoryId("dir-rec-1");
        series.setFolderNodeId(IdUtil.nextId());
        series.setSeriesName("联动扫描测试剧");
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

    private MediaEpisode insertEpisode(MediaSeries series, MediaSeason season, int episodeNo, boolean watched) {
        MediaEpisode episode = new MediaEpisode();
        episode.setSeriesId(series.getId());
        episode.setSeasonId(season.getId());
        episode.setEpisodeNo(episodeNo);
        episode.setWatched(watched);
        episode.setProgressMs(0L);
        mediaEpisodeMapper.insert(episode);
        MediaEpisodeFile file = new MediaEpisodeFile();
        file.setEpisodeId(episode.getId());
        file.setFileNodeId(IdUtil.nextId());
        file.setDurationMs(100_000L);
        mediaEpisodeFileMapper.insert(file);
        return episode;
    }
}
