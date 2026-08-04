package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaScanStatus;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaDirectorySourceMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileRenameDto;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.support.MediaProbeSupport;
import com.fleyx.jcloud.util.FilePathUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * 电视媒体库新模型扫描与查询服务测试（issue #17，ADR 0021）。
 * <p>
 * 严格三层「来源目录/剧/季/集」识别、文件夹/文件节点锚定 upsert、按剧即时 reconcile、
 * 批次扫描时间三道闸清理、级联删除（连带 t_media_metadata_v2）、库内闭环。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaTvScanServiceTest {

    private static final MediaProbeResult PROBE = new MediaProbeResult(
            3_600_000L, "matroska", "h264", "aac", 1920, 1080, null, List.of(), List.of());

    @Autowired
    private FileService fileService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private FileOperationService fileOperationService;

    @Autowired
    private MediaScanService mediaScanService;

    @Autowired
    private MediaItemService mediaItemService;

    @Autowired
    private MediaDirectoryMapper mediaDirectoryMapper;

    @Autowired
    private MediaDirectorySourceMapper mediaDirectorySourceMapper;

    @Autowired
    private MediaSeriesMapper mediaSeriesMapper;

    @Autowired
    private MediaSeasonMapper mediaSeasonMapper;

    @Autowired
    private MediaEpisodeMapper mediaEpisodeMapper;

    @Autowired
    private MediaEpisodeFileMapper mediaEpisodeFileMapper;

    @Autowired
    private MediaMetadataMapper mediaMetadataMapper;

    @Autowired
    private FileMapper fileMapper;

    @MockitoBean
    private MediaScrapeService mediaScrapeService;

    @MockitoBean
    private MediaProbeSupport mediaProbeSupport;

    @TempDir
    Path tempDir;

    @BeforeEach
    void stubProbe() {
        lenient().when(mediaProbeSupport.probe(any(Path.class))).thenReturn(PROBE);
        lenient().when(mediaProbeSupport.probe(any(InputStream.class))).thenReturn(PROBE);
    }

    /**
     * 三层落表与锚定：剧/季/集/集文件四层数据正确落入新表，锚定字段与解析结果正确；
     * 剧文件夹下散落视频丢弃、超过三层的文件忽略、无集号文件丢弃；同一集多个文件并入为多版本；
     * 批次扫描时间写入剧行；重扫幂等（锚定 upsert 不重复建行）。
     */
    @Test
    void shouldScanTvLibraryIntoNewTablesWithAnchors() {
        UserVo user = prepareUserWithStorageSpace();
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

    /**
     * 剧文件夹重命名（非 manual）后重扫：按锚找到同一剧行，仅更新标题字段与首播年份，
     * 匹配状态重置为 unmatched 并清空元数据关联；集播放进度保留。
     */
    @Test
    void shouldResetNonManualMatchWhenSeriesFolderRenamed() {
        UserVo user = prepareUserWithStorageSpace();
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
        UserVo user = prepareUserWithStorageSpace();
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
        UserVo user = prepareUserWithStorageSpace();
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
        UserVo user = prepareUserWithStorageSpace();
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
        UserVo user = prepareUserWithStorageSpace();
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
     * 删除一集文件后重扫：该集与其集文件明细即时删除，季与剧保留。
     */
    @Test
    void shouldDeleteEpisodeImmediatelyWhenFileRemoved() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "火星生活");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        upload(user.getId(), seasonFolder.getId(), "火星生活.S01E01.mkv");
        FileNodeVo e02 = upload(user.getId(), seasonFolder.getId(), "火星生活.S01E02.mkv");
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());

        fileMapper.deleteById(e02.getId());
        mediaScanService.scan(directory.getId());

        MediaSeries series = querySingleSeries(directory.getId());
        List<MediaEpisode> episodes = episodesOfSeries(series.getId());
        assertEquals(1, episodes.size());
        assertEquals(1, episodes.getFirst().getEpisodeNo());
        assertEquals(1, mediaEpisodeFileMapper.selectCount(null));
        assertEquals(1, seasonsOfSeries(series.getId()).size());
        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
    }

    /**
     * 剧集入库时间取当前集文件明细最新创建时间：重扫不变，新增后续版本更新，删除最新明细后回退；
     * 同时保留最早文件修改时间语义。
     */
    @Test
    void shouldMaintainSeriesAddedTimeAndMinFileLastModifiedAcrossBoundaries() {
        UserVo user = prepareUserWithStorageSpace();
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
    }

    /**
     * 删除季文件夹后重扫：该季及其集/集文件被即时删除（连带 metadata_v2 行），同剧其他季完好，无孤儿。
     */
    @Test
    void shouldDeleteSeasonImmediatelyWhenSeasonFolderRemoved() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "火星生活");
        FileNodeVo season1Folder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        upload(user.getId(), season1Folder.getId(), "火星生活.S01E01.mkv");
        FileNodeVo season2Folder = createFolder(user.getId(), seriesFolder.getId(), "Season 2");
        upload(user.getId(), season2Folder.getId(), "火星生活.S02E01.mkv");
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());

        MediaSeries series = querySingleSeries(directory.getId());
        assertEquals(2, seasonsOfSeries(series.getId()).size());
        MediaSeason season2 = seasonsOfSeries(series.getId()).stream()
                .filter(s -> s.getSeasonNo() == 2).findFirst().orElseThrow();
        MediaEpisode season2Episode = episodesOfSeries(series.getId()).stream()
                .filter(e -> e.getSeasonId().equals(season2.getId())).findFirst().orElseThrow();
        seedMetadata(user.getId(), "season", season2.getId());
        seedMetadata(user.getId(), "episode", season2Episode.getId());

        purgeSubtree(user.getId(), season2Folder.getId());
        mediaScanService.scan(directory.getId());

        List<MediaSeason> seasons = seasonsOfSeries(series.getId());
        assertEquals(1, seasons.size());
        assertEquals(1, seasons.getFirst().getSeasonNo());
        assertNull(mediaSeasonMapper.selectById(season2.getId()));
        assertNull(mediaEpisodeMapper.selectById(season2Episode.getId()));
        assertEquals(0, mediaEpisodeFileMapper.selectCount(new LambdaQueryWrapper<MediaEpisodeFile>()
                .eq(MediaEpisodeFile::getEpisodeId, season2Episode.getId())));
        assertEquals(0, mediaMetadataMapper.selectCount(null));
        // 同剧第一季完好
        assertEquals(1, episodesOfSeries(series.getId()).size());
        assertEquals(1, mediaEpisodeFileMapper.selectCount(null));
        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
    }

    /**
     * 删除剧文件夹后重扫：批次清理级联删除剧/季/集/集文件，连带 metadata_v2 行，无孤儿。
     */
    @Test
    void shouldCascadeDeleteWhenSeriesFolderRemoved() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "火星生活");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        upload(user.getId(), seasonFolder.getId(), "火星生活.S01E01.mkv");
        FileNodeVo otherSeriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo otherSeasonFolder = createFolder(user.getId(), otherSeriesFolder.getId(), "Season 1");
        upload(user.getId(), otherSeasonFolder.getId(), "亮剑.S01E01.mkv");
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());
        assertEquals(2, mediaSeriesMapper.selectCount(null));

        MediaSeries removed = mediaSeriesMapper.selectOne(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getFolderNodeId, seriesFolder.getId()));
        MediaSeason removedSeason = seasonsOfSeries(removed.getId()).getFirst();
        MediaEpisode removedEpisode = episodesOfSeries(removed.getId()).getFirst();
        // 模拟削刮写入的元数据（owner 反向指针），验证级联连带删除
        seedMetadata(user.getId(), "series", removed.getId());
        seedMetadata(user.getId(), "season", removedSeason.getId());
        seedMetadata(user.getId(), "episode", removedEpisode.getId());

        purgeSubtree(user.getId(), seriesFolder.getId());
        mediaScanService.scan(directory.getId());

        assertNull(mediaSeriesMapper.selectById(removed.getId()));
        assertNull(mediaSeasonMapper.selectById(removedSeason.getId()));
        assertNull(mediaEpisodeMapper.selectById(removedEpisode.getId()));
        assertEquals(0, mediaEpisodeFileMapper.selectCount(new LambdaQueryWrapper<MediaEpisodeFile>()
                .eq(MediaEpisodeFile::getEpisodeId, removedEpisode.getId())));
        assertEquals(0, mediaMetadataMapper.selectCount(null));
        // 另一部剧完好
        assertEquals(1, mediaSeriesMapper.selectCount(null));
        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
    }

    /**
     * 来源目录不可达（远程挂载掉线）时扫描记为 PARTIAL，且其下条目不被批次清理；
     * 恢复完整扫描后，合格来源下消失的剧才被清理，不合格来源（已出库）的剧不结算。
     */
    @Test
    void shouldNotCleanupWhenSourceUnreachableOrPartial() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolderA = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视A");
        FileNodeVo seriesFolderA = createFolder(user.getId(), tvFolderA.getId(), "火星生活");
        FileNodeVo seasonFolderA = createFolder(user.getId(), seriesFolderA.getId(), "Season 1");
        upload(user.getId(), seasonFolderA.getId(), "火星生活.S01E01.mkv");
        FileNodeVo tvFolderB = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视B");
        FileNodeVo seriesFolderB = createFolder(user.getId(), tvFolderB.getId(), "亮剑");
        FileNodeVo seasonFolderB = createFolder(user.getId(), seriesFolderB.getId(), "Season 1");
        upload(user.getId(), seasonFolderB.getId(), "亮剑.S01E01.mkv");

        MediaDirectory directory = createTvDirectory(user.getId(), tvFolderA.getId());
        addSource(directory.getId(), tvFolderB.getId());
        mediaScanService.scan(directory.getId());
        assertEquals(2, mediaSeriesMapper.selectCount(null));

        // 来源 A 掉线 + 来源 B 下的剧被删除：本轮扫描 PARTIAL，两道闸均不清理
        purgeSubtree(user.getId(), tvFolderA.getId());
        purgeSubtree(user.getId(), seriesFolderB.getId());
        mediaScanService.scan(directory.getId());

        assertEquals(MediaScanStatus.PARTIAL.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
        assertEquals(2, mediaSeriesMapper.selectCount(null));

        // 来源 A 出库后完整重扫：合格来源 B 下消失的剧被批次清理；来源 A 不结算（由目录管理负责清空）
        mediaDirectorySourceMapper.delete(new LambdaQueryWrapper<MediaDirectorySource>()
                .eq(MediaDirectorySource::getDirectoryId, directory.getId())
                .eq(MediaDirectorySource::getFileNodeId, tvFolderA.getId()));
        mediaScanService.scan(directory.getId());

        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
        List<MediaSeries> remaining = mediaSeriesMapper.selectList(null);
        assertEquals(1, remaining.size());
        assertEquals("火星生活", remaining.getFirst().getSeriesName());
    }

    /**
     * 多库隔离：同名剧跨库各建一行（库级归属）；一个库的扫描与清理不影响另一个库。
     */
    @Test
    void shouldIsolateCleanupWithinDirectory() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder1 = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视一");
        FileNodeVo seriesFolder1 = createFolder(user.getId(), tvFolder1.getId(), "火星生活");
        FileNodeVo seasonFolder1 = createFolder(user.getId(), seriesFolder1.getId(), "Season 1");
        upload(user.getId(), seasonFolder1.getId(), "火星生活.S01E01.mkv");
        FileNodeVo tvFolder2 = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视二");
        FileNodeVo seriesFolder2 = createFolder(user.getId(), tvFolder2.getId(), "火星生活");
        FileNodeVo seasonFolder2 = createFolder(user.getId(), seriesFolder2.getId(), "Season 1");
        upload(user.getId(), seasonFolder2.getId(), "火星生活.S01E01.mkv");

        MediaDirectory directory1 = createTvDirectory(user.getId(), tvFolder1.getId());
        MediaDirectory directory2 = createTvDirectory(user.getId(), tvFolder2.getId());
        mediaScanService.scan(directory1.getId());
        mediaScanService.scan(directory2.getId());
        // 同名剧跨库各建一行
        assertEquals(2, mediaSeriesMapper.selectCount(null));

        // 库 1 的剧文件夹删除后重扫库 1：仅库 1 数据被清理
        purgeSubtree(user.getId(), seriesFolder1.getId());
        mediaScanService.scan(directory1.getId());

        assertEquals(0, mediaSeriesMapper.selectCount(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getDirectoryId, directory1.getId())));
        assertEquals(1, mediaSeriesMapper.selectCount(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getDirectoryId, directory2.getId())));
        assertEquals(1, mediaEpisodeMapper.selectCount(null));
    }

    /**
     * 电视海报墙与详情 API 返回新表数据：海报墙按剧聚合（集数、匹配状态、媒体库过滤）、
     * 剧详情季卡片、按季剧集列表（代表文件、进度）、集详情回退新表。
     */
    @Test
    void shouldServePosterWallAndDetailFromNewTables() {
        UserVo user = prepareUserWithStorageSpace();
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

    /**
     * 季文件夹跨剧移动（同库同来源）后重扫：季行/集行/集文件行 ID、播放进度与元数据保留（issue #17 F1）。
     * 移动的子项由目标剧在阶段一按锚认领改挂，源剧在阶段二的删除被守卫跳过，进度不丢失。
     */
    @Test
    void shouldPreserveSeasonRowsWhenSeasonMovedAcrossSeries() {
        UserVo user = prepareUserWithStorageSpace();
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
        UserVo user = prepareUserWithStorageSpace();
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

    private void seedMetadata(String userId, String ownerType, String ownerId) {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setUserId(userId);
        metadata.setOwnerType(ownerType);
        metadata.setOwnerId(ownerId);
        metadata.setSource("tmdb");
        metadata.setTitle("测试元数据");
        mediaMetadataMapper.insert(metadata);
    }

    private MediaSeries querySingleSeries(String directoryId) {
        return mediaSeriesMapper.selectOne(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getDirectoryId, directoryId));
    }

    private List<MediaSeason> seasonsOfSeries(String seriesId) {
        return mediaSeasonMapper.selectList(new LambdaQueryWrapper<MediaSeason>()
                .eq(MediaSeason::getSeriesId, seriesId));
    }

    private List<MediaEpisode> episodesOfSeries(String seriesId) {
        return mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                .eq(MediaEpisode::getSeriesId, seriesId));
    }

    private List<MediaEpisodeFile> filesOfEpisode(String episodeId) {
        return mediaEpisodeFileMapper.selectList(new LambdaQueryWrapper<MediaEpisodeFile>()
                .eq(MediaEpisodeFile::getEpisodeId, episodeId));
    }

    /**
     * 物理删除文件夹及其整个子树节点（模拟文件夹被删除或远程挂载掉线）。
     */
    private void purgeSubtree(String userId, String folderNodeId) {
        FileNode folder = fileMapper.selectById(folderNodeId);
        if (folder == null) {
            return;
        }
        List<FileNode> descendants = fileMapper.selectByIdPathPrefix(userId, folder.getPath(), folder.getId());
        if (!descendants.isEmpty()) {
            fileMapper.deleteBatchIds(descendants.stream().map(FileNode::getId).toList());
        }
        fileMapper.deleteById(folderNodeId);
    }

    /**
     * 直接改文件节点的父节点与物化路径，模拟跨季/跨剧移动（绕过两阶段冲突解决流程）。
     */
    private void moveFileNode(String userId, String fileNodeId, String targetFolderNodeId) {
        FileNode file = fileMapper.selectById(fileNodeId);
        FileNode targetFolder = fileMapper.selectById(targetFolderNodeId);
        FileNode update = new FileNode();
        update.setId(file.getId());
        update.setParentId(targetFolder.getId());
        update.setPath(FilePathUtil.fullIdPath(targetFolder));
        fileMapper.updateById(update);
    }

    /**
     * 直接改文件夹节点的父节点与物化路径，并同步子树所有节点的物化路径前缀（模拟跨剧移动季文件夹）。
     */
    private void moveFolderNode(String userId, String folderNodeId, String targetParentNodeId) {
        FileNode folder = fileMapper.selectById(folderNodeId);
        FileNode targetParent = fileMapper.selectById(targetParentNodeId);
        String oldPath = folder.getPath();
        String newPath = FilePathUtil.fullIdPath(targetParent);
        List<FileNode> subtree = fileMapper.selectByIdPathPrefix(userId, oldPath, folderNodeId);
        FileNode update = new FileNode();
        update.setId(folder.getId());
        update.setParentId(targetParent.getId());
        update.setPath(newPath);
        fileMapper.updateById(update);
        String oldPrefix = oldPath + FileNodeConstants.PATH_SEPARATOR;
        String newPrefix = newPath + FileNodeConstants.PATH_SEPARATOR;
        for (FileNode node : subtree) {
            if (node.getId().equals(folder.getId())) {
                continue;
            }
            FileNode descendant = new FileNode();
            descendant.setId(node.getId());
            descendant.setPath(newPrefix + node.getPath().substring(oldPrefix.length()));
            fileMapper.updateById(descendant);
        }
    }

    private MediaSeries querySeriesByFolder(String folderNodeId) {
        return mediaSeriesMapper.selectOne(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getFolderNodeId, folderNodeId));
    }

    private void rename(String userId, String nodeId, String newName) {
        FileRenameDto dto = new FileRenameDto();
        dto.setId(nodeId);
        dto.setNewName(newName);
        fileOperationService.rename(dto, userId);
    }

    private MediaDirectory createTvDirectory(String userId, String folderNodeId) {
        MediaDirectory directory = new MediaDirectory();
        directory.setUserId(userId);
        directory.setName("测试电视库");
        directory.setMediaType("tv");
        mediaDirectoryMapper.insert(directory);
        addSource(directory.getId(), folderNodeId);
        return directory;
    }

    private void addSource(String directoryId, String folderNodeId) {
        MediaDirectorySource source = new MediaDirectorySource();
        source.setDirectoryId(directoryId);
        source.setFileNodeId(folderNodeId);
        mediaDirectorySourceMapper.insert(source);
    }

    private FileNodeVo upload(String userId, String parentId, String name) {
        MultipartFile file = new MockMultipartFile("file", name, "video/x-matroska", "video".getBytes());
        return fileService.upload(file, userId, parentId, null);
    }

    private FileNodeVo createFolder(String userId, String parentId, String name) {
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(parentId);
        dto.setName(name);
        return fileOperationService.createFolder(dto, userId);
    }

    private UserVo prepareUserWithStorageSpace() {
        Path spacePath = tempDir.resolve("space-" + System.nanoTime());
        StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
        spaceDto.setName("用户空间");
        spaceDto.setPath(spacePath.toString());
        StorageSpaceVo space = storageSpaceService.save(spaceDto);

        UserSaveDto userDto = new UserSaveDto();
        userDto.setUsername("user_" + Long.toUnsignedString(System.nanoTime(), 36));
        userDto.setPassword("123456");
        userDto.setStorageSpaceId(space.getId());
        userDto.setQuota(10L);
        userDto.setQuotaUnit("GB");
        UserVo user = userService.saveUser(userDto);
        UserContext.set(new CurrentUser(user.getId(), user.getUsername()));
        return user;
    }
}
