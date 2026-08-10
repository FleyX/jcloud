package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaScanStatus;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesVo;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 电视媒体库扫描落表与锚定、聚合与查询服务测试（issue #17，ADR 0021）。
 * <p>
 * 严格三层「来源目录/剧/季/集」识别、文件夹/文件节点锚定 upsert（重扫幂等）、
 * 剧集聚合完整性在扫描末尾重算、入库时间/最早文件修改时间语义，以及海报墙与详情新表查询。
 */
@Transactional
class MediaTvScanAnchorTest extends MediaTvScanTestBase {

    /**
     * 三层落表与锚定：剧/季/集/集文件四层数据正确落入新表，锚定字段与解析结果正确；
     * 剧文件夹下散落视频丢弃、超过三层的文件忽略、无集号文件丢弃；同一集多个文件并入为多版本；
     * 批次扫描时间写入剧行；重扫幂等（锚定 upsert 不重复建行）。
     */
    @Test
    void shouldScanTvLibraryIntoNewTablesWithAnchors() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "火星生活 (2018)");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo e01 = upload(user.getId(), seasonFolder.getId(), "火星生活.S01E01.1080p.mkv");
        FileNodeVo e01v2 = upload(user.getId(), seasonFolder.getId(), "火星生活.S01E01.4K.mkv");
        FileNodeVo e02 = upload(user.getId(), seasonFolder.getId(), "火星生活.S01E02.mkv");
        // 剧文件夹下散落视频：直接丢弃（取消旧的「归入 S01」行为）
        upload(user.getId(), seriesFolder.getId(), "火星生活.S01E03.mkv");
        // 超过三层的文件：忽略
        FileNodeVo deepFolder = createFolder(user.getId(), seasonFolder.getId(), "extra");
        upload(user.getId(), deepFolder.getId(), "火星生活.S01E04.mkv");
        // 季文件夹下无集号文件：丢弃
        upload(user.getId(), seasonFolder.getId(), "火星生活.特典.mkv");

        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());
        // 重扫幂等：锚定 upsert 不重复建行
        mediaScanService.scan(directory.getId());

        MediaSeries series = querySingleSeries(directory.getId());
        assertEquals(seriesFolder.getId(), series.getFolderNodeId());
        assertEquals("火星生活", series.getSeriesName());
        assertEquals(2018, series.getReleaseYear());
        assertEquals(directory.getId(), series.getDirectoryId());
        assertNotNull(series.getSourceId());
        assertNotNull(series.getScanTime());
        assertEquals(MediaMatchStatus.UNMATCHED.getCode(), series.getMatchStatus());
        assertEquals(Boolean.FALSE, series.getMetadataComplete());
        assertNotNull(series.getMinFileLastModified());
        assertNotNull(series.getLatestAddedTime());

        List<MediaSeason> seasons = seasonsOfSeries(series.getId());
        assertEquals(1, seasons.size());
        assertEquals(seasonFolder.getId(), seasons.getFirst().getFolderNodeId());
        assertEquals(1, seasons.getFirst().getSeasonNo());

        List<MediaEpisode> episodes = episodesOfSeries(series.getId());
        assertEquals(2, episodes.size());
        MediaEpisode episode1 = episodes.stream().filter(e -> e.getEpisodeNo() == 1).findFirst().orElseThrow();
        MediaEpisode episode2 = episodes.stream().filter(e -> e.getEpisodeNo() == 2).findFirst().orElseThrow();
        assertEquals(seasons.getFirst().getId(), episode1.getSeasonId());
        assertEquals(series.getId(), episode1.getSeriesId());

        // 同一集的两个文件并入为多版本；集文件锚 = 视频文件节点
        List<MediaEpisodeFile> episode1Files = filesOfEpisode(episode1.getId());
        assertEquals(2, episode1Files.size());
        assertTrue(episode1Files.stream().anyMatch(f -> f.getFileNodeId().equals(e01.getId())));
        assertTrue(episode1Files.stream().anyMatch(f -> f.getFileNodeId().equals(e01v2.getId())));
        List<MediaEpisodeFile> episode2Files = filesOfEpisode(episode2.getId());
        assertEquals(1, episode2Files.size());
        assertEquals(e02.getId(), episode2Files.getFirst().getFileNodeId());
        // ffprobe 探测结果与文件变更哈希落库
        assertEquals(3_600_000L, episode1Files.getFirst().getDurationMs());
        assertNotNull(episode1Files.getFirst().getFileHash());

        // 散文件/超三层/无集号文件均未落表
        assertEquals(3, mediaEpisodeFileMapper.selectCount(null));
        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
        // 扫描完成后自动提交一次非强制削刮
        verify(mediaScrapeService, atLeastOnce()).submitScrape(directory.getId(), user.getId(), false);
    }

    // ---------- 票据 01：扫描链路表征测试补齐（增量 diff / 批次清理闸③ / FAILED 结局） ----------

    /**
     * 集文件内容变更（同路径同名，仅文件大小变化）后重扫：变更哈希变化 → 重跑 ffprobe 并更新明细行哈希，
     * 明细行/集行 ID 与文件锚不变；播放进度与 manual 匹配保留；未变化重扫哈希命中跳过探测。
     */
    @Test
    void shouldUpdateFileHashAndReprobeWhenEpisodeFileChanged() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "火星生活");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo video = upload(user.getId(), seasonFolder.getId(), "火星生活.S01E01.mkv");
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());

        MediaSeries series = querySingleSeries(directory.getId());
        series.setMatchStatus(MediaMatchStatus.MANUAL.getCode());
        series.setMetadataId("metaseries001");
        mediaSeriesMapper.updateById(series);
        MediaEpisode episode = episodesOfSeries(series.getId()).getFirst();
        episode.setProgressMs(5000L);
        mediaEpisodeMapper.updateById(episode);
        MediaEpisodeFile fileRow = filesOfEpisode(episode.getId()).getFirst();
        String oldHash = fileRow.getFileHash();

        // 未变化重扫：哈希命中跳过探测
        mediaScanService.scan(directory.getId());
        verify(mediaProbeSupport, times(1)).probe(any(Path.class));

        // 修改文件内容但保持路径与文件名：文件大小变化 → 变更哈希（含大小）变化 → 重走解析并重跑 ffprobe
        FileNode fileNode = fileMapper.selectById(video.getId());
        FileNode update = new FileNode();
        update.setId(video.getId());
        update.setSize(fileNode.getSize() + 100L);
        fileMapper.updateById(update);
        mediaScanService.scan(directory.getId());

        verify(mediaProbeSupport, times(2)).probe(any(Path.class));
        MediaEpisodeFile after = mediaEpisodeFileMapper.selectById(fileRow.getId());
        assertNotNull(after);
        assertEquals(fileRow.getId(), after.getId());
        assertNotEquals(oldHash, after.getFileHash());
        assertEquals(video.getId(), after.getFileNodeId());
        MediaEpisode afterEpisode = episodesOfSeries(series.getId()).getFirst();
        assertEquals(episode.getId(), afterEpisode.getId());
        assertEquals(5000L, afterEpisode.getProgressMs());
        MediaSeries afterSeries = querySingleSeries(directory.getId());
        assertEquals(series.getId(), afterSeries.getId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), afterSeries.getMatchStatus());
        assertEquals("metaseries001", afterSeries.getMetadataId());
    }

    // ---------- issue #02：剧集聚合完整性在扫描末尾重算（poster 存在性校验） ----------

    /**
     * 删除季海报 FileNode 后重扫：剧集聚合完整性变 false（季元数据 poster 校验项升级为
     * 指针非空且 FileNode 真实存在，扫描末尾对本库条目重算）；
     * 对照组：未删产物的剧扫描后完整性不变（无误判）。
     */
    @Test
    void shouldRecomputeSeriesAggregateCompleteAfterScan() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        // 剧 A：后续删除季海报
        FileNodeVo seriesA = createFolder(user.getId(), tvFolder.getId(), "火星生活");
        FileNodeVo seasonA = createFolder(user.getId(), seriesA.getId(), "Season 1");
        upload(user.getId(), seasonA.getId(), "火星生活.S01E01.mkv");
        FileNodeVo seriesAPoster = upload(user.getId(), seriesA.getId(), "poster.jpg");
        FileNodeVo seasonAPoster = upload(user.getId(), seriesA.getId(), "season01-poster.jpg");
        FileNodeVo epAStill = upload(user.getId(), seasonA.getId(), "still.jpg");
        // 剧 B：对照组，产物保持完好
        FileNodeVo seriesB = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonB = createFolder(user.getId(), seriesB.getId(), "Season 1");
        upload(user.getId(), seasonB.getId(), "亮剑.S01E01.mkv");
        FileNodeVo seriesBPoster = upload(user.getId(), seriesB.getId(), "poster.jpg");
        FileNodeVo seasonBPoster = upload(user.getId(), seriesB.getId(), "season01-poster.jpg");
        FileNodeVo epBStill = upload(user.getId(), seasonB.getId(), "still.jpg");
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());

        MediaSeries seriesARow = querySeriesByFolder(seriesA.getId());
        MediaSeries seriesBRow = querySeriesByFolder(seriesB.getId());
        MediaSeason seasonArow = seasonsOfSeries(seriesARow.getId()).getFirst();
        MediaSeason seasonBrow = seasonsOfSeries(seriesBRow.getId()).getFirst();
        MediaEpisode episodeA = episodesOfSeries(seriesARow.getId()).getFirst();
        MediaEpisode episodeB = episodesOfSeries(seriesBRow.getId()).getFirst();
        seedCompleteSeries(user.getId(), seriesARow, seasonArow, episodeA,
                seriesAPoster.getId(), seasonAPoster.getId(), epAStill.getId());
        seedCompleteSeries(user.getId(), seriesBRow, seasonBrow, episodeB,
                seriesBPoster.getId(), seasonBPoster.getId(), epBStill.getId());

        // 对照组：未删产物的剧扫描后完整性不变
        mediaScanService.scan(directory.getId());
        assertEquals(Boolean.TRUE, mediaSeriesMapper.selectById(seriesBRow.getId()).getMetadataComplete());

        fileMapper.physicalDeleteById(seasonAPoster.getId());
        mediaScanService.scan(directory.getId());

        assertEquals(Boolean.FALSE, mediaSeriesMapper.selectById(seriesARow.getId()).getMetadataComplete());
        assertEquals(Boolean.TRUE, mediaSeriesMapper.selectById(seriesBRow.getId()).getMetadataComplete());
    }

    /**
     * 剧集入库时间取当前集文件明细最新创建时间：重扫不变，新增后续版本更新，删除最新明细后回退；
     * 同时保留最早文件修改时间语义。
     */
    @Test
    void shouldMaintainSeriesAddedTimeAndMinFileLastModifiedAcrossBoundaries() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "火星生活");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo first = upload(user.getId(), seasonFolder.getId(), "火星生活.S01E01.mkv");
        FileNodeVo second = upload(user.getId(), seasonFolder.getId(), "火星生活.S01E02.mkv");
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());

        LocalDateTime firstAdded = LocalDateTime.of(2020, 1, 1, 0, 0);
        LocalDateTime secondAdded = LocalDateTime.of(2020, 1, 2, 0, 0);
        MediaSeries series = querySingleSeries(directory.getId());
        MediaEpisodeFile firstRow = mediaEpisodeFileMapper.selectOne(new LambdaQueryWrapper<MediaEpisodeFile>()
                .eq(MediaEpisodeFile::getFileNodeId, first.getId()));
        MediaEpisodeFile secondRow = mediaEpisodeFileMapper.selectOne(new LambdaQueryWrapper<MediaEpisodeFile>()
                .eq(MediaEpisodeFile::getFileNodeId, second.getId()));
        firstRow.setCreateTime(firstAdded);
        firstRow.setFileLastModified(200L);
        secondRow.setCreateTime(secondAdded);
        secondRow.setFileLastModified(300L);
        mediaEpisodeFileMapper.updateById(firstRow);
        mediaEpisodeFileMapper.updateById(secondRow);

        mediaScanService.scan(directory.getId());
        MediaSeries afterRescan = querySingleSeries(directory.getId());
        assertEquals(secondAdded, afterRescan.getLatestAddedTime());
        assertEquals(200L, afterRescan.getMinFileLastModified());
        mediaScanService.scan(directory.getId());
        assertEquals(secondAdded, querySingleSeries(directory.getId()).getLatestAddedTime());

        FileNodeVo later = upload(user.getId(), seasonFolder.getId(), "火星生活.S01E03.mkv");
        mediaScanService.scan(directory.getId());
        MediaEpisodeFile laterRow = mediaEpisodeFileMapper.selectOne(new LambdaQueryWrapper<MediaEpisodeFile>()
                .eq(MediaEpisodeFile::getFileNodeId, later.getId()));
        LocalDateTime latest = LocalDateTime.of(2020, 1, 3, 0, 0);
        laterRow.setCreateTime(latest);
        laterRow.setFileLastModified(100L);
        mediaEpisodeFileMapper.updateById(laterRow);
        mediaScanService.scan(directory.getId());
        afterRescan = querySingleSeries(directory.getId());
        assertEquals(latest, afterRescan.getLatestAddedTime());
        assertEquals(100L, afterRescan.getMinFileLastModified());

        fileMapper.deleteById(later.getId());
        mediaScanService.scan(directory.getId());
        MediaSeries afterDelete = querySingleSeries(directory.getId());
        assertEquals(series.getId(), afterDelete.getId());
        assertEquals(secondAdded, afterDelete.getLatestAddedTime());

        // 删除全部集文件后重扫：剧文件夹已无视频，剧行被批次清理（V14 语义：无明细不残留最近入库时间）
        fileMapper.deleteById(first.getId());
        fileMapper.deleteById(second.getId());
        mediaScanService.scan(directory.getId());
        assertNull(mediaSeriesMapper.selectById(series.getId()));
    }

    /**
     * 电视海报墙与详情 API 返回新表数据：海报墙按剧聚合（集数、匹配状态、媒体库过滤）、
     * 剧详情季卡片、按季剧集列表（代表文件、进度）、集详情回退新表。
     */
    @Test
    void shouldServePosterWallAndDetailFromNewTables() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "火星生活 (2018)");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo e01 = upload(user.getId(), seasonFolder.getId(), "火星生活.S01E01.1080p.mkv");
        upload(user.getId(), seasonFolder.getId(), "火星生活.S01E02.mkv");
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());

        MediaSeries series = querySingleSeries(directory.getId());
        MediaEpisode episode1 = episodesOfSeries(series.getId()).stream()
                .filter(e -> e.getEpisodeNo() == 1).findFirst().orElseThrow();
        episode1.setProgressMs(5000L);
        episode1.setLastPlayTime(LocalDateTime.now());
        mediaEpisodeMapper.updateById(episode1);

        // 海报墙
        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setDirectoryId(directory.getId());
        IPage<MediaSeriesVo> page = mediaItemService.listSeries(user.getId(), query);
        assertEquals(1L, page.getTotal());
        MediaSeriesVo card = page.getRecords().getFirst();
        assertEquals(series.getId(), card.getId());
        assertEquals("火星生活", card.getSeriesName());
        assertEquals("火星生活", card.getTitle());
        assertEquals(2L, card.getEpisodeCount());
        assertEquals(MediaMatchStatus.UNMATCHED.getCode(), card.getMatchStatus());
        assertNotNull(card.getLastPlayTime());
        // 跨库过滤：不传 directoryId 同样命中；不存在库则空
        assertEquals(1L, mediaItemService.listSeries(user.getId(), new MediaPageQueryDto()).getTotal());
        MediaPageQueryDto otherQuery = new MediaPageQueryDto();
        otherQuery.setDirectoryId("not-exists-dir");
        assertEquals(0L, mediaItemService.listSeries(user.getId(), otherQuery).getTotal());

        // 剧详情：季卡片含集数与观看进度标记
        MediaSeriesDetailVo detail = mediaItemService.getSeriesDetail(series.getId(), user.getId());
        assertEquals("火星生活", detail.getSeriesName());
        assertEquals("火星生活", detail.getTitle());
        assertEquals(1, detail.getSeasons().size());
        assertEquals(1, detail.getSeasons().getFirst().getSeasonNo());
        assertEquals(2L, detail.getSeasons().getFirst().getEpisodeCount());
        assertTrue(detail.getSeasons().getFirst().getHasProgress());

        // 按季剧集列表：按集号升序，代表文件定位到上传的视频节点
        List<MediaItemVo> episodes = mediaItemService.listSeasonEpisodes(
                series.getId(), detail.getSeasons().getFirst().getSeasonId(), user.getId());
        assertEquals(2, episodes.size());
        assertEquals(1, episodes.get(0).getEpisodeNo());
        assertEquals(2, episodes.get(1).getEpisodeNo());
        assertEquals(e01.getId(), episodes.get(0).getFileNodeId());
        assertEquals("episode", episodes.get(0).getItemType());
        assertEquals(5000L, episodes.get(0).getProgressMs());
        assertEquals(3_600_000L, episodes.get(0).getDurationMs());
        assertEquals(series.getId(), episodes.get(0).getSeriesId());
        assertEquals("火星生活", episodes.get(0).getSeriesName());
        assertEquals(2, mediaItemService.listEpisodes(series.getId(), user.getId()).size());

        // 集详情（新表回退）
        MediaItemDetailVo episodeDetail = mediaItemService.getItemDetail(episode1.getId(), user.getId());
        assertEquals(episode1.getId(), episodeDetail.getId());
        assertEquals("episode", episodeDetail.getItemType());
        assertEquals(1, episodeDetail.getSeasonNo());
        assertEquals(1, episodeDetail.getEpisodeNo());
        assertEquals("火星生活", episodeDetail.getSeriesName());
        assertEquals("火星生活.S01E01.1080p.mkv", episodeDetail.getFileName());
        assertEquals(5000L, episodeDetail.getProgressMs());
        assertEquals(1920, episodeDetail.getWidth());
    }
}
