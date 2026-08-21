package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.MediaProgressUpdateDto;
import com.fleyx.jcloud.model.dto.MediaWatchedUpdateDto;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesSeasonVo;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 剧集/季三级联动服务测试（工单 02）。
 * <p>
 * 覆盖验收标准：向下级联（剧→季+集、季→集、取消同理）、向上聚合（集全部已观看→季/剧自动置位）、
 * 向上清除（集变为未观看→季/剧自动清除）、VO 输出带 watched。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaWatchedLinkageServiceTest {

    private static final String USER_ID = "watcher000002";

    @Autowired
    private MediaItemService mediaItemService;
    @Autowired
    private MediaEpisodeMapper mediaEpisodeMapper;
    @Autowired
    private MediaEpisodeFileMapper mediaEpisodeFileMapper;
    @Autowired
    private MediaSeasonMapper mediaSeasonMapper;
    @Autowired
    private MediaSeriesMapper mediaSeriesMapper;

    /**
     * 标记剧已观看：整剧所有季、集全置 true，且集进度清零。
     */
    @Test
    void cascadeSeriesMarksAllSubtreeWatchedAndClearsProgress() {
        MediaSeries series = insertSeries();
        MediaSeason s1 = insertSeason(series, 1);
        MediaSeason s2 = insertSeason(series, 2);
        MediaEpisode e1 = insertEpisode(series, s1, 1, false, 50_000L);
        MediaEpisode e2 = insertEpisode(series, s2, 1, false, 0L);

        mediaItemService.updateWatched(series.getId(), dto(true), USER_ID);

        assertTrue(Boolean.TRUE.equals(mediaSeriesMapper.selectById(series.getId()).getWatched()));
        assertTrue(Boolean.TRUE.equals(mediaSeasonMapper.selectById(s1.getId()).getWatched()));
        assertTrue(Boolean.TRUE.equals(mediaSeasonMapper.selectById(s2.getId()).getWatched()));
        assertEquals(0L, mediaEpisodeMapper.selectById(e1.getId()).getProgressMs().longValue());
        assertTrue(Boolean.TRUE.equals(mediaEpisodeMapper.selectById(e1.getId()).getWatched()));
        assertTrue(Boolean.TRUE.equals(mediaEpisodeMapper.selectById(e2.getId()).getWatched()));
    }

    /**
     * 取消剧已观看：整剧所有季、集全清 false。
     */
    @Test
    void cascadeSeriesUnmarkClearsAllSubtree() {
        MediaSeries series = insertSeries();
        MediaSeason s1 = insertSeason(series, 1);
        MediaEpisode e1 = insertEpisode(series, s1, 1, true, 0L);
        mediaItemService.updateWatched(series.getId(), dto(true), USER_ID);
        assertTrue(Boolean.TRUE.equals(mediaSeasonMapper.selectById(s1.getId()).getWatched()));

        mediaItemService.updateWatched(series.getId(), dto(false), USER_ID);

        assertFalse(Boolean.TRUE.equals(mediaSeriesMapper.selectById(series.getId()).getWatched()));
        assertFalse(Boolean.TRUE.equals(mediaSeasonMapper.selectById(s1.getId()).getWatched()));
        assertFalse(Boolean.TRUE.equals(mediaEpisodeMapper.selectById(e1.getId()).getWatched()));
    }

    /**
     * 标记季已观看：该季所有集置 true 且进度清零；同剧另一季未看故剧仍为未看。
     */
    @Test
    void cascadeSeasonMarksItsEpisodesAndSeriesStaysUnwatchedWhenOthersPend() {
        MediaSeries series = insertSeries();
        MediaSeason s1 = insertSeason(series, 1);
        MediaSeason s2 = insertSeason(series, 2);
        insertEpisode(series, s1, 1, false, 50_000L);
        insertEpisode(series, s2, 1, false, 0L);

        mediaItemService.updateWatched(s1.getId(), dto(true), USER_ID);

        assertTrue(Boolean.TRUE.equals(mediaSeasonMapper.selectById(s1.getId()).getWatched()));
        assertFalse(Boolean.TRUE.equals(mediaSeriesMapper.selectById(series.getId()).getWatched()));
        List<MediaEpisode> s1Eps = mediaEpisodeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MediaEpisode>()
                        .eq(MediaEpisode::getSeasonId, s1.getId()));
        assertEquals(1, s1Eps.size());
        assertTrue(Boolean.TRUE.equals(s1Eps.getFirst().getWatched()));
        assertEquals(0L, s1Eps.getFirst().getProgressMs().longValue());
    }

    /**
     * 单季剧：标记季已观看，向上聚合 → 剧自动置位。
     */
    @Test
    void cascadeSeasonOnSingleSeasonAggregatesSeriesUp() {
        MediaSeries series = insertSeries();
        MediaSeason s1 = insertSeason(series, 1);
        insertEpisode(series, s1, 1, false, 0L);

        mediaItemService.updateWatched(s1.getId(), dto(true), USER_ID);

        assertTrue(Boolean.TRUE.equals(mediaSeasonMapper.selectById(s1.getId()).getWatched()));
        assertTrue(Boolean.TRUE.equals(mediaSeriesMapper.selectById(series.getId()).getWatched()));
    }

    /**
     * 取消季已观看：其集全 false，且所在剧（原已全部看完）自动清除。
     */
    @Test
    void cascadeSeasonUnmarkClearsSeriesUp() {
        MediaSeries series = insertSeries();
        MediaSeason s1 = insertSeason(series, 1);
        insertEpisode(series, s1, 1, true, 0L);
        mediaItemService.updateWatched(series.getId(), dto(true), USER_ID);
        assertTrue(Boolean.TRUE.equals(mediaSeriesMapper.selectById(series.getId()).getWatched()));

        mediaItemService.updateWatched(s1.getId(), dto(false), USER_ID);

        assertFalse(Boolean.TRUE.equals(mediaSeasonMapper.selectById(s1.getId()).getWatched()));
        assertFalse(Boolean.TRUE.equals(mediaSeriesMapper.selectById(series.getId()).getWatched()));
    }

    /**
     * 手动标记最后一集已观看：季、剧向上聚合自动置位（一次联动的 end-to-end）。
     */
    @Test
    void markingLastEpisodeWatchedAggregatesParentsUp() {
        MediaSeries series = insertSeries();
        MediaSeason s1 = insertSeason(series, 1);
        insertEpisode(series, s1, 1, true, 0L);
        MediaEpisode last = insertEpisode(series, s1, 2, false, 0L);
        assertFalse(Boolean.TRUE.equals(mediaSeasonMapper.selectById(s1.getId()).getWatched()));

        mediaItemService.updateWatched(last.getId(), dto(true), USER_ID);

        assertTrue(Boolean.TRUE.equals(mediaSeasonMapper.selectById(s1.getId()).getWatched()));
        assertTrue(Boolean.TRUE.equals(mediaSeriesMapper.selectById(series.getId()).getWatched()));
    }

    /**
     * 某集变为未观看（手动取消）：所在季、剧标记自动清除。
     */
    @Test
    void markingEpisodeUnwatchedClearsParentsUp() {
        MediaSeries series = insertSeries();
        MediaSeason s1 = insertSeason(series, 1);
        MediaEpisode e1 = insertEpisode(series, s1, 1, true, 0L);
        insertEpisode(series, s1, 2, true, 0L);
        mediaItemService.updateWatched(series.getId(), dto(true), USER_ID);
        assertTrue(Boolean.TRUE.equals(mediaSeriesMapper.selectById(series.getId()).getWatched()));

        mediaItemService.updateWatched(e1.getId(), dto(false), USER_ID);

        assertFalse(Boolean.TRUE.equals(mediaEpisodeMapper.selectById(e1.getId()).getWatched()));
        assertFalse(Boolean.TRUE.equals(mediaSeasonMapper.selectById(s1.getId()).getWatched()));
        assertFalse(Boolean.TRUE.equals(mediaSeriesMapper.selectById(series.getId()).getWatched()));
    }

    /**
     * 进度达看完阈值自动置集 watched（progress-driven）→ 父级联动聚合。
     */
    @Test
    void progressDrivenWatchAggregatesParentsUp() {
        MediaSeries series = insertSeries();
        MediaSeason s1 = insertSeason(series, 1);
        insertEpisode(series, s1, 1, true, 0L);
        insertEpisode(series, s1, 2, false, 0L);

        MediaEpisode last = mediaEpisodeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MediaEpisode>()
                        .eq(MediaEpisode::getSeasonId, s1.getId()).eq(MediaEpisode::getEpisodeNo, 2))
                .getFirst();
        MediaProgressUpdateDto pdto = new MediaProgressUpdateDto();
        pdto.setProgressMs(95_000L);
        mediaItemService.updateProgress(last.getId(), pdto, USER_ID);

        assertTrue(Boolean.TRUE.equals(mediaEpisodeMapper.selectById(last.getId()).getWatched()));
        assertTrue(Boolean.TRUE.equals(mediaSeasonMapper.selectById(s1.getId()).getWatched()));
        assertTrue(Boolean.TRUE.equals(mediaSeriesMapper.selectById(series.getId()).getWatched()));
    }

    /**
     * VO 输出带 watched：海报墙剧卡、季卡片、剧详情均填充聚合后的标记。
     */
    @Test
    void vosCarryAggregatedWatched() {
        MediaSeries series = insertSeries();
        MediaSeason s1 = insertSeason(series, 1);
        insertEpisode(series, s1, 1, true, 0L);
        mediaItemService.updateWatched(series.getId(), dto(true), USER_ID);

        MediaSeriesDetailVo detailVo = mediaItemService.getSeriesDetail(series.getId(), USER_ID);
        assertTrue(Boolean.TRUE.equals(detailVo.getWatched()));
        MediaSeriesSeasonVo seasonVo = detailVo.getSeasons().stream()
                .filter(v -> v.getSeasonId().equals(s1.getId())).findFirst().orElseThrow();
        assertTrue(Boolean.TRUE.equals(seasonVo.getWatched()));

        // 海报墙剧卡带 watched（经 listSeries 走 toCard 装配）
        com.baomidou.mybatisplus.core.metadata.IPage<MediaSeriesVo> page = mediaItemService.listSeries(USER_ID,
                new com.fleyx.jcloud.model.dto.MediaPageQueryDto());
        MediaSeriesVo card = page.getRecords().stream()
                .filter(v -> v.getId().equals(series.getId())).findFirst().orElseThrow();
        assertTrue(Boolean.TRUE.equals(card.getWatched()));

        // 集列表 VO 仍按集行 watched 填充（顺带校验 episode 集）
        MediaItemVo epVo = mediaItemService.listEpisodes(series.getId(), USER_ID).getFirst();
        assertTrue(epVo.getWatched());
    }

    /**
     * 手动标记季/剧已观看应清零集进度；季/剧 VO 不影响 hasProgress 语义（仍按 progressMs>0）。
     */
    @Test
    void markSeriesClearsProgressButKeepsHasProgressSemantics() {
        MediaSeries series = insertSeries();
        MediaSeason s1 = insertSeason(series, 1);
        MediaEpisode e1 = insertEpisode(series, s1, 1, false, 80_000L);
        mediaItemService.updateWatched(series.getId(), dto(true), USER_ID);
        assertEquals(0L, mediaEpisodeMapper.selectById(e1.getId()).getProgressMs().longValue());

        MediaSeriesDetailVo detailVo = mediaItemService.getSeriesDetail(series.getId(), USER_ID);
        MediaSeriesSeasonVo seasonVo = detailVo.getSeasons().getFirst();
        assertFalse(Boolean.TRUE.equals(seasonVo.getHasProgress()));
    }

    private MediaWatchedUpdateDto dto(boolean watched) {
        MediaWatchedUpdateDto dto = new MediaWatchedUpdateDto();
        dto.setWatched(watched);
        return dto;
    }

    private MediaSeries insertSeries() {
        MediaSeries series = new MediaSeries();
        series.setUserId(USER_ID);
        series.setDirectoryId("dir-wl-001");
        series.setFolderNodeId(IdUtil.nextId());
        series.setSeriesName("联动测试剧");
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
                                       boolean watched, Long progressMs) {
        MediaEpisode episode = new MediaEpisode();
        episode.setSeriesId(series.getId());
        episode.setSeasonId(season.getId());
        episode.setEpisodeNo(episodeNo);
        episode.setWatched(watched);
        episode.setProgressMs(progressMs);
        mediaEpisodeMapper.insert(episode);
        MediaEpisodeFile file = new MediaEpisodeFile();
        file.setEpisodeId(episode.getId());
        file.setFileNodeId(IdUtil.nextId());
        file.setDurationMs(100_000L);
        mediaEpisodeFileMapper.insert(file);
        return episode;
    }
}
