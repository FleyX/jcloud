package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 电视媒体库重命名与移动 reanchor 测试（issue #17 F1）。
 * <p>
 * 剧/季/集文件夹重命名或集文件跨季、跨剧移动后重扫：行按文件夹锚认领改挂（reanchor），
 * 行 ID、播放进度与元数据保留，manual 匹配受保护，合并/清空不留孤儿行。
 */
@Transactional
class MediaTvScanReanchorTest extends MediaTvScanTestBase {

    /**
     * 剧文件夹重命名（非 manual）后重扫：按锚找到同一剧行，仅更新标题字段与首播年份，
     * 匹配状态重置为 unmatched 并清空元数据关联；集播放进度保留。
     */
    @Test
    void shouldResetNonManualMatchWhenSeriesFolderRenamed() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "火星生活");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "s01");
        upload(user.getId(), seasonFolder.getId(), "火星生活.s01e01.mp4");
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());

        // 模拟削刮完成状态 + 播放进度
        MediaSeries series = querySingleSeries(directory.getId());
        series.setMetadataId("metaseries001");
        series.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
        mediaSeriesMapper.updateById(series);
        MediaEpisode episode = episodesOfSeries(series.getId()).getFirst();
        episode.setProgressMs(5000L);
        mediaEpisodeMapper.updateById(episode);

        rename(user.getId(), seriesFolder.getId(), "火星生活 (2018)");
        mediaScanService.scan(directory.getId());

        MediaSeries after = querySingleSeries(directory.getId());
        assertEquals(series.getId(), after.getId());
        assertEquals("火星生活", after.getSeriesName());
        assertEquals(2018, after.getReleaseYear());
        assertEquals(MediaMatchStatus.UNMATCHED.getCode(), after.getMatchStatus());
        assertNull(after.getMetadataId());
        MediaEpisode afterEpisode = episodesOfSeries(series.getId()).getFirst();
        assertEquals(episode.getId(), afterEpisode.getId());
        assertEquals(5000L, afterEpisode.getProgressMs());
    }

    /**
     * 剧文件夹重命名（manual）后重扫：匹配状态与元数据关联保留，仅标题字段更新。
     */
    @Test
    void shouldKeepManualMatchWhenSeriesFolderRenamed() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "火星生活");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "s01");
        upload(user.getId(), seasonFolder.getId(), "火星生活.s01e01.mp4");
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());

        MediaSeries series = querySingleSeries(directory.getId());
        series.setMetadataId("metaseries001");
        series.setMatchStatus(MediaMatchStatus.MANUAL.getCode());
        mediaSeriesMapper.updateById(series);

        rename(user.getId(), seriesFolder.getId(), "火星生活 (2018)");
        mediaScanService.scan(directory.getId());

        MediaSeries after = querySingleSeries(directory.getId());
        assertEquals(series.getId(), after.getId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), after.getMatchStatus());
        assertEquals("metaseries001", after.getMetadataId());
        assertEquals(2018, after.getReleaseYear());
    }

    /**
     * 季文件夹重命名后重扫：按锚找到同一季行，季号更新，集与进度保留。
     */
    @Test
    void shouldUpdateSeasonNoWhenSeasonFolderRenamed() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "火星生活");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        upload(user.getId(), seasonFolder.getId(), "火星生活.S01E01.mkv");
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());

        MediaSeries series = querySingleSeries(directory.getId());
        MediaEpisode episode = episodesOfSeries(series.getId()).getFirst();
        episode.setProgressMs(8000L);
        mediaEpisodeMapper.updateById(episode);
        MediaSeason season = seasonsOfSeries(series.getId()).getFirst();

        rename(user.getId(), seasonFolder.getId(), "Season 2");
        mediaScanService.scan(directory.getId());

        MediaSeason afterSeason = seasonsOfSeries(series.getId()).getFirst();
        assertEquals(season.getId(), afterSeason.getId());
        assertEquals(2, afterSeason.getSeasonNo());
        MediaEpisode afterEpisode = episodesOfSeries(series.getId()).getFirst();
        assertEquals(episode.getId(), afterEpisode.getId());
        assertEquals(8000L, afterEpisode.getProgressMs());
    }

    /**
     * 集文件跨季移动（目标季无同号集）：集行按锚改挂到新季，集行 ID 与播放进度保留。
     */
    @Test
    void shouldReanchorEpisodeWhenFileMovedAcrossSeasons() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "火星生活");
        FileNodeVo season1 = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo season2 = createFolder(user.getId(), seriesFolder.getId(), "Season 2");
        upload(user.getId(), season1.getId(), "火星生活.S01E01.mkv");
        FileNodeVo e02 = upload(user.getId(), season1.getId(), "火星生活.S01E02.mkv");
        upload(user.getId(), season2.getId(), "火星生活.S02E01.mkv");
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());

        MediaSeries series = querySingleSeries(directory.getId());
        MediaEpisode moved = episodesOfSeries(series.getId()).stream()
                .filter(e -> e.getEpisodeNo() == 2).findFirst().orElseThrow();
        moved.setProgressMs(5000L);
        mediaEpisodeMapper.updateById(moved);

        moveFileNode(user.getId(), e02.getId(), season2.getId());
        mediaScanService.scan(directory.getId());

        MediaSeason season2Row = seasonsOfSeries(series.getId()).stream()
                .filter(s -> s.getSeasonNo() == 2).findFirst().orElseThrow();
        MediaEpisode after = episodesOfSeries(series.getId()).stream()
                .filter(e -> e.getEpisodeNo() == 2).findFirst().orElseThrow();
        assertEquals(moved.getId(), after.getId());
        assertEquals(season2Row.getId(), after.getSeasonId());
        assertEquals(5000L, after.getProgressMs());
        assertEquals(e02.getId(), filesOfEpisode(after.getId()).getFirst().getFileNodeId());
        // 第一季仅剩 E01，无孤儿集
        assertEquals(3, mediaEpisodeMapper.selectCount(null));
    }

    /**
     * 集文件跨季移动（目标季已有同号集）：明细行并入作为版本，被清空的源集行删除，进度保留目标行。
     */
    @Test
    void shouldMergeAsVersionWhenTargetEpisodeExists() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "火星生活");
        FileNodeVo season1 = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo season2 = createFolder(user.getId(), seriesFolder.getId(), "Season 2");
        FileNodeVo source = upload(user.getId(), season1.getId(), "火星生活.S01E01.mkv");
        upload(user.getId(), season2.getId(), "火星生活.S02E01.mkv");
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());

        MediaSeries series = querySingleSeries(directory.getId());
        MediaSeason season1Row = seasonsOfSeries(series.getId()).stream()
                .filter(s -> s.getSeasonNo() == 1).findFirst().orElseThrow();
        MediaEpisode sourceEpisode = episodesOfSeries(series.getId()).stream()
                .filter(e -> e.getSeasonId().equals(season1Row.getId())).findFirst().orElseThrow();
        sourceEpisode.setProgressMs(5000L);
        mediaEpisodeMapper.updateById(sourceEpisode);
        MediaEpisode targetEpisode = episodesOfSeries(series.getId()).stream()
                .filter(e -> !e.getSeasonId().equals(season1Row.getId())).findFirst().orElseThrow();
        targetEpisode.setProgressMs(3000L);
        mediaEpisodeMapper.updateById(targetEpisode);

        moveFileNode(user.getId(), source.getId(), season2.getId());
        mediaScanService.scan(directory.getId());

        // 源集行被清空后删除，目标集行保留自身进度，源文件并入为目标集的版本
        List<MediaEpisode> episodes = episodesOfSeries(series.getId());
        assertEquals(1, episodes.size());
        assertEquals(targetEpisode.getId(), episodes.getFirst().getId());
        assertEquals(3000L, episodes.getFirst().getProgressMs());
        List<MediaEpisodeFile> files = filesOfEpisode(targetEpisode.getId());
        assertEquals(2, files.size());
        assertTrue(files.stream().anyMatch(f -> f.getFileNodeId().equals(source.getId())));
        // 第一季文件夹已无有效文件：季行即时删除，无孤儿
        assertEquals(1, mediaSeasonMapper.selectCount(null));
        assertNull(mediaEpisodeMapper.selectById(sourceEpisode.getId()));
    }

    /**
     * 季文件夹跨剧移动（同库同来源）后重扫：季行/集行/集文件行 ID、播放进度与元数据保留（issue #17 F1）。
     * 移动的子项由目标剧在阶段一按锚认领改挂，源剧在阶段二的删除被守卫跳过，进度不丢失。
     */
    @Test
    void shouldPreserveSeasonRowsWhenSeasonMovedAcrossSeries() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesA = createFolder(user.getId(), tvFolder.getId(), "剧甲");
        FileNodeVo seasonA1 = createFolder(user.getId(), seriesA.getId(), "Season 1");
        FileNodeVo e01 = upload(user.getId(), seasonA1.getId(), "剧甲.S01E01.mkv");
        FileNodeVo seriesB = createFolder(user.getId(), tvFolder.getId(), "剧乙");
        FileNodeVo seasonB2 = createFolder(user.getId(), seriesB.getId(), "Season 2");
        upload(user.getId(), seasonB2.getId(), "剧乙.S02E01.mkv");
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());

        MediaSeries seriesArow = querySeriesByFolder(seriesA.getId());
        MediaSeries seriesBrow = querySeriesByFolder(seriesB.getId());
        MediaSeason seasonRow = seasonsOfSeries(seriesArow.getId()).getFirst();
        MediaEpisode episodeRow = episodesOfSeries(seriesArow.getId()).getFirst();
        episodeRow.setProgressMs(5000L);
        mediaEpisodeMapper.updateById(episodeRow);
        seedMetadata(user.getId(), "season", seasonRow.getId());
        seedMetadata(user.getId(), "episode", episodeRow.getId());
        MediaEpisodeFile fileRow = filesOfEpisode(episodeRow.getId()).getFirst();

        moveFolderNode(user.getId(), seasonA1.getId(), seriesB.getId());
        mediaScanService.scan(directory.getId());

        // 季行改挂到剧乙：ID 与文件夹锚不变，集行/明细行 ID 与进度保留，元数据未删
        MediaSeason afterSeason = mediaSeasonMapper.selectById(seasonRow.getId());
        assertNotNull(afterSeason);
        assertEquals(seriesBrow.getId(), afterSeason.getSeriesId());
        assertEquals(seasonA1.getId(), afterSeason.getFolderNodeId());
        MediaEpisode afterEpisode = mediaEpisodeMapper.selectById(episodeRow.getId());
        assertNotNull(afterEpisode);
        assertEquals(seriesBrow.getId(), afterEpisode.getSeriesId());
        assertEquals(afterSeason.getId(), afterEpisode.getSeasonId());
        assertEquals(5000L, afterEpisode.getProgressMs());
        MediaEpisodeFile afterFile = mediaEpisodeFileMapper.selectById(fileRow.getId());
        assertNotNull(afterFile);
        assertEquals(afterEpisode.getId(), afterFile.getEpisodeId());
        assertEquals(e01.getId(), afterFile.getFileNodeId());
        // 源剧已无季/集；目标剧接管两季两集；季/集元数据随行保留
        assertEquals(0, seasonsOfSeries(seriesArow.getId()).size());
        assertEquals(0, episodesOfSeries(seriesArow.getId()).size());
        assertEquals(2, seasonsOfSeries(seriesBrow.getId()).size());
        assertEquals(2, episodesOfSeries(seriesBrow.getId()).size());
        assertEquals(2, mediaMetadataMapper.selectCount(null));
    }

    /**
     * 集文件跨剧移动（同库同来源）后重扫：集行/明细行 ID、播放进度与元数据保留（issue #17 F1）；
     * 源剧未清空的集与季保留，无孤儿行。
     */
    @Test
    void shouldPreserveRowsAndProgressWhenFileMovedAcrossSeries() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesA = createFolder(user.getId(), tvFolder.getId(), "剧甲");
        FileNodeVo seasonA1 = createFolder(user.getId(), seriesA.getId(), "Season 1");
        upload(user.getId(), seasonA1.getId(), "剧甲.S01E01.mkv");
        FileNodeVo e02 = upload(user.getId(), seasonA1.getId(), "剧甲.S01E02.mkv");
        FileNodeVo seriesB = createFolder(user.getId(), tvFolder.getId(), "剧乙");
        FileNodeVo seasonB1 = createFolder(user.getId(), seriesB.getId(), "Season 1");
        upload(user.getId(), seasonB1.getId(), "剧乙.S01E01.mkv");
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());

        MediaSeries seriesArow = querySeriesByFolder(seriesA.getId());
        MediaSeries seriesBrow = querySeriesByFolder(seriesB.getId());
        MediaEpisode movedEpisode = episodesOfSeries(seriesArow.getId()).stream()
                .filter(e -> e.getEpisodeNo() == 2).findFirst().orElseThrow();
        movedEpisode.setProgressMs(5000L);
        mediaEpisodeMapper.updateById(movedEpisode);
        seedMetadata(user.getId(), "episode", movedEpisode.getId());
        MediaEpisodeFile movedFile = filesOfEpisode(movedEpisode.getId()).getFirst();

        moveFileNode(user.getId(), e02.getId(), seasonB1.getId());
        mediaScanService.scan(directory.getId());

        // 集行改挂到剧乙的季：ID、进度、元数据保留；明细行 ID 保留
        MediaEpisode after = mediaEpisodeMapper.selectById(movedEpisode.getId());
        assertNotNull(after);
        assertEquals(seriesBrow.getId(), after.getSeriesId());
        assertEquals(5000L, after.getProgressMs());
        MediaSeason seasonB1Row = seasonsOfSeries(seriesBrow.getId()).stream()
                .filter(s -> s.getSeasonNo() == 1).findFirst().orElseThrow();
        assertEquals(seasonB1Row.getId(), after.getSeasonId());
        assertEquals(e02.getId(), mediaEpisodeFileMapper.selectById(movedFile.getId()).getFileNodeId());
        assertEquals(1, mediaMetadataMapper.selectCount(null));
        // 源剧保留 E01；两剧共 3 集 3 明细，无孤儿
        assertEquals(1, episodesOfSeries(seriesArow.getId()).size());
        assertEquals(3, mediaEpisodeMapper.selectCount(null));
        assertEquals(3, mediaEpisodeFileMapper.selectCount(null));
    }
}
