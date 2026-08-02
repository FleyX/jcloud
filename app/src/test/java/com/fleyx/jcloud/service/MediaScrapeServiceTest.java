package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaScrapeStatus;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaDirectorySourceMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 媒体库削刮服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaScrapeServiceTest {

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
    private MediaScrapeService mediaScrapeService;

    @Autowired
    private MediaDirectoryMapper mediaDirectoryMapper;

    @Autowired
    private MediaDirectorySourceMapper mediaDirectorySourceMapper;

    @Autowired
    private MediaItemMapper mediaItemMapper;

    @Autowired
    private MediaSeriesMapper mediaSeriesMapper;

    @Autowired
    private MediaSeasonMapper mediaSeasonMapper;

    @Autowired
    private MediaMetadataMapper mediaMetadataMapper;

    @Autowired
    private FileMapper fileMapper;

    @MockitoBean
    private TmdbService tmdbService;

    @TempDir
    Path tempDir;

    /**
     * 电影削刮：文件名匹配失败时用直接父目录名兜底。
     */
    @Test
    void shouldFallbackToParentDirNameWhenFileNameNotMatched() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo parentFolder = createFolder(user.getId(), movieFolder.getId(), "Iron Man 2008");
        fileService.upload(buildFile("randomfile.mkv"), user.getId(), parentFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());

        MediaMetadata metadata = buildMetadata("metamovie0001", 1000L);
        when(tmdbService.autoMatch(eq(user.getId()), eq("movie"), anyString(), any()))
                .thenAnswer(inv -> "Iron Man".equals(inv.getArgument(2)) ? metadata : null);

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaItem item = queryItem(directory.getId());
        assertEquals("metamovie0001", item.getMetadataId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), item.getMatchStatus());
        assertEquals(MediaScrapeStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScrapeStatus());
    }

    /**
     * 手动修正的条目削刮时跳过（含 force）。
     */
    @Test
    void shouldSkipManualItemWhenScraping() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());

        MediaItem item = queryItem(directory.getId());
        item.setMetadataId("manualmeta001");
        item.setMatchStatus(MediaMatchStatus.MANUAL.getCode());
        mediaItemMapper.updateById(item);

        scrapeAwaitIdle(directory, user.getId(), true);

        verify(tmdbService, never()).autoMatch(any(), anyString(), anyString(), any());
        MediaItem after = queryItem(directory.getId());
        assertEquals("manualmeta001", after.getMetadataId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), after.getMatchStatus());
    }

    /**
     * 电视削刮：剧级匹配应用到季与非手动集，拉取季/集元数据。
     * <p>
     * 电视库扫描已切换到新模型（issue #17），旧削刮路径（#20 替换）的测试直接播种旧表数据。
     */
    @Test
    void shouldScrapeSeriesAndFillSeasonEpisodeMetadata() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo episodeFile = fileService.upload(buildFile("亮剑.S01E01.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        seedOldTvScanData(directory, "亮剑", null, 1, 1, episodeFile.getId());

        MediaMetadata seriesMetadata = buildMetadata("metatv0000001", 2000L);
        MediaMetadata seasonMetadata = buildMetadata("metaseason001", null);
        MediaMetadata episodeMetadata = buildMetadata("metaepisode01", null);
        when(tmdbService.autoMatch(eq(user.getId()), eq("tv"), eq("亮剑"), isNull())).thenReturn(seriesMetadata);
        when(tmdbService.getOrFetchSeason(eq(user.getId()), eq(2000L), any(MediaSeason.class)))
                .thenReturn(seasonMetadata);
        when(tmdbService.getOrFetchEpisode(eq(user.getId()), any(MediaItem.class), eq(seasonMetadata)))
                .thenReturn(episodeMetadata);

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaSeries series = mediaSeriesMapper.selectOne(
                new LambdaQueryWrapper<MediaSeries>().eq(MediaSeries::getUserId, user.getId()));
        assertEquals("metatv0000001", series.getMetadataId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), series.getMatchStatus());

        MediaSeason season = mediaSeasonMapper.selectOne(
                new LambdaQueryWrapper<MediaSeason>().eq(MediaSeason::getSeriesId, series.getId()));
        assertEquals("metaseason001", season.getMetadataId());

        MediaItem item = queryItem(directory.getId());
        assertEquals("metaepisode01", item.getMetadataId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), item.getMatchStatus());
    }

    /**
     * 电视削刮：剧匹配失败标记未匹配，不产生季/集元数据请求。
     */
    @Test
    void shouldMarkSeriesUnmatchedWhenScrapeFailed() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "不存在的剧xyz");
        FileNodeVo episodeFile = fileService.upload(buildFile("xyz.S01E01.mkv"), user.getId(), seriesFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        seedOldTvScanData(directory, "不存在的剧xyz", null, null, 1, episodeFile.getId());

        when(tmdbService.autoMatch(eq(user.getId()), eq("tv"), anyString(), isNull())).thenReturn(null);

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaSeries series = mediaSeriesMapper.selectOne(
                new LambdaQueryWrapper<MediaSeries>().eq(MediaSeries::getUserId, user.getId()));
        assertEquals(MediaMatchStatus.UNMATCHED.getCode(), series.getMatchStatus());
        assertNull(series.getMetadataId());
        verify(tmdbService, never()).getOrFetchSeason(any(), any(), any());
    }

    /**
     * 电视削刮：使用剧文件夹解析出的首播年份进行匹配。
     */
    @Test
    void shouldScrapeSeriesWithReleaseYear() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑 (2005)");
        FileNodeVo episodeFile = fileService.upload(buildFile("亮剑.S01E01.mkv"), user.getId(), seriesFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        MediaSeries series = seedOldTvScanData(directory, "亮剑", 2005, 1, 1, episodeFile.getId());
        assertEquals(2005, series.getReleaseYear());

        MediaMetadata seriesMetadata = buildMetadata("metatv0000002", 3000L);
        when(tmdbService.autoMatch(eq(user.getId()), eq("tv"), eq("亮剑"), eq(2005))).thenReturn(seriesMetadata);

        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService).autoMatch(user.getId(), "tv", "亮剑", 2005);
        MediaSeries after = mediaSeriesMapper.selectById(series.getId());
        assertEquals("metatv0000002", after.getMetadataId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), after.getMatchStatus());
    }

    /**
     * 电影削刮本地优先：同目录存在同名 .nfo 时不请求 TMDB，
     * 元数据来源 local_nfo、字段与完整性正确，海报/背景绑定本地图片；
     * local_nfo 来源不写回——用户原有 nfo 不被规范化重写覆盖（ADR 0020）。
     */
    @Test
    void shouldScrapeMovieFromLocalNfoWithoutTmdb() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo parentFolder = createFolder(user.getId(), movieFolder.getId(), "Iron Man 2008");
        fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildTextFile("Iron.Man.2008.1080p.nfo", """
                <movie>
                  <tmdbid>1726</tmdbid>
                  <title>钢铁侠</title>
                  <originaltitle>Iron Man</originaltitle>
                  <plot>托尼·斯塔克打造钢铁战衣</plot>
                  <premiered>2008-04-30</premiered>
                  <rating>7.6</rating>
                  <genre>科幻</genre>
                  <studio>Marvel Studios</studio>
                </movie>
                """), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildFile("poster.jpg"), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildFile("fanart.jpg"), user.getId(), parentFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());

        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService, never()).autoMatch(any(), anyString(), anyString(), any());
        MediaItem item = queryItem(directory.getId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), item.getMatchStatus());
        MediaMetadata metadata = mediaMetadataMapper.selectById(item.getMetadataId());
        assertEquals("local_nfo", metadata.getSource());
        assertEquals(1726L, metadata.getTmdbId());
        assertEquals("钢铁侠", metadata.getTitle());
        assertEquals("2008-04-30", metadata.getReleaseDate());
        assertEquals("complete", metadata.getCompleteStatus());
        assertEquals("persisted", metadata.getPersistStatus());
        assertEquals(queryChildNode(parentFolder.getId(), "poster.jpg").getId(), metadata.getPosterFileNodeId());
        assertEquals(queryChildNode(parentFolder.getId(), "fanart.jpg").getId(), metadata.getBackdropFileNodeId());
        // local_nfo 来源不写回：用户原有 nfo 内容保持原样
        FileNode nfoNode = queryChildNode(parentFolder.getId(), "Iron.Man.2008.1080p.nfo");
        String nfoContent = downloadText(nfoNode.getId(), user.getId());
        assertTrue(nfoContent.contains("<studio>Marvel Studios</studio>"), "用户原有 nfo 不应被规范化重写覆盖");
    }

    /**
     * 电影削刮 TMDB 路径写回：匹配成功后下载图片并写入视频目录（poster.jpg/fanart.jpg + 同名 nfo）。
     */
    @Test
    void shouldPersistArtworkAfterTmdbScrape() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());

        MediaMetadata metadata = buildMetadata("metatmdb00001", 1000L);
        metadata.setUserId(user.getId());
        metadata.setMediaType("movie");
        metadata.setSource("tmdb");
        metadata.setCompleteStatus("complete");
        metadata.setPersistStatus("pending");
        metadata.setTitle("钢铁侠");
        metadata.setRawJson("{\"poster_path\":\"/p.jpg\",\"backdrop_path\":\"/b.jpg\"}");
        mediaMetadataMapper.insert(metadata);
        when(tmdbService.autoMatch(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(metadata);
        when(tmdbService.downloadArtwork("/p.jpg", "poster")).thenReturn(new byte[]{1, 2, 3});
        when(tmdbService.downloadArtwork("/b.jpg", "backdrop")).thenReturn(new byte[]{4, 5});

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaItem item = queryItem(directory.getId());
        assertEquals("metatmdb00001", item.getMetadataId());
        MediaMetadata after = mediaMetadataMapper.selectById("metatmdb00001");
        assertEquals("persisted", after.getPersistStatus());
        FileNode poster = queryChildNode(movieFolder.getId(), "poster.jpg");
        FileNode fanart = queryChildNode(movieFolder.getId(), "fanart.jpg");
        FileNode nfo = queryChildNode(movieFolder.getId(), "Iron.Man.2008.1080p.nfo");
        assertEquals(poster.getId(), after.getPosterFileNodeId());
        assertEquals(fanart.getId(), after.getBackdropFileNodeId());
        assertEquals(3L, poster.getSize());
        assertEquals(2L, fanart.getSize());
        assertTrue(nfo.getSize() > 0);
    }

    /**
     * 写回失败（图片下载失败）：元数据正常保留并绑定，persist_status=failed，削刮结果不受影响。
     */
    @Test
    void shouldKeepMetadataWhenPersistFails() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());

        MediaMetadata metadata = buildMetadata("metatmdb00002", 1001L);
        metadata.setUserId(user.getId());
        metadata.setMediaType("movie");
        metadata.setSource("tmdb");
        metadata.setCompleteStatus("complete");
        metadata.setPersistStatus("pending");
        metadata.setTitle("钢铁侠");
        metadata.setRawJson("{\"poster_path\":\"/p.jpg\"}");
        mediaMetadataMapper.insert(metadata);
        when(tmdbService.autoMatch(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(metadata);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(null);

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaItem item = queryItem(directory.getId());
        assertEquals("metatmdb00002", item.getMetadataId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), item.getMatchStatus());
        MediaMetadata after = mediaMetadataMapper.selectById("metatmdb00002");
        assertEquals("failed", after.getPersistStatus());
        assertEquals("钢铁侠", after.getTitle());
        assertEquals(MediaScrapeStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScrapeStatus());
    }

    /**
     * 电视剧削刮本地优先：tvshow.nfo + 季海报 + 集 nfo/剧照全本地绑定，不请求 TMDB；
     * 本地来源不写回，用户原有 tvshow.nfo 不被规范化重写覆盖。
     */
    @Test
    void shouldScrapeSeriesFromLocalNfoWithoutTmdb() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo episodeFile = fileService.upload(buildFile("亮剑.S01E01.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        fileService.upload(buildTextFile("tvshow.nfo", """
                <tvshow>
                  <tmdbid>2000</tmdbid>
                  <title>亮剑</title>
                  <plot>李云龙抗战传奇</plot>
                  <premiered>2005-09-12</premiered>
                  <rating>9.4</rating>
                  <studio>八一电影制片厂</studio>
                </tvshow>
                """), user.getId(), seriesFolder.getId(), null);
        fileService.upload(buildFile("poster.jpg"), user.getId(), seriesFolder.getId(), null);
        fileService.upload(buildFile("season01-poster.jpg"), user.getId(), seriesFolder.getId(), null);
        fileService.upload(buildTextFile("亮剑.S01E01.1080p.nfo", """
                <episodedetails>
                  <title>苍云岭之战</title>
                  <plot>李云龙击溃坂田联队</plot>
                  <season>1</season>
                  <episode>1</episode>
                </episodedetails>
                """), user.getId(), seasonFolder.getId(), null);
        fileService.upload(buildFile("亮剑.S01E01.1080p-thumb.jpg"), user.getId(), seasonFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        seedOldTvScanData(directory, "亮剑", null, 1, 1, episodeFile.getId());

        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService, never()).autoMatch(any(), anyString(), anyString(), any());
        verify(tmdbService, never()).getOrFetchSeason(any(), any(), any());
        MediaSeries series = mediaSeriesMapper.selectOne(
                new LambdaQueryWrapper<MediaSeries>().eq(MediaSeries::getUserId, user.getId()));
        assertEquals(MediaMatchStatus.MATCHED.getCode(), series.getMatchStatus());
        MediaMetadata seriesMetadata = mediaMetadataMapper.selectById(series.getMetadataId());
        assertEquals("local_nfo", seriesMetadata.getSource());
        assertEquals(2000L, seriesMetadata.getTmdbId());
        assertEquals("亮剑", seriesMetadata.getTitle());
        assertEquals("complete", seriesMetadata.getCompleteStatus());
        assertEquals(queryChildNode(seriesFolder.getId(), "poster.jpg").getId(), seriesMetadata.getPosterFileNodeId());

        MediaSeason season = mediaSeasonMapper.selectOne(
                new LambdaQueryWrapper<MediaSeason>().eq(MediaSeason::getSeriesId, series.getId()));
        MediaMetadata seasonMetadata = mediaMetadataMapper.selectById(season.getMetadataId());
        assertEquals("local_nfo", seasonMetadata.getSource());
        assertEquals(queryChildNode(seriesFolder.getId(), "season01-poster.jpg").getId(),
                seasonMetadata.getPosterFileNodeId());

        MediaItem item = queryItem(directory.getId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), item.getMatchStatus());
        MediaMetadata episodeMetadata = mediaMetadataMapper.selectById(item.getMetadataId());
        assertEquals("local_nfo", episodeMetadata.getSource());
        assertEquals("episode", episodeMetadata.getMediaType());
        assertEquals("苍云岭之战", episodeMetadata.getTitle());
        assertEquals("complete", episodeMetadata.getCompleteStatus());
        assertEquals(queryChildNode(seasonFolder.getId(), "亮剑.S01E01.1080p-thumb.jpg").getId(),
                episodeMetadata.getPosterFileNodeId());
        // 本地来源不写回：用户原有 tvshow.nfo 内容保持原样
        String tvshowNfo = downloadText(queryChildNode(seriesFolder.getId(), "tvshow.nfo").getId(), user.getId());
        assertTrue(tvshowNfo.contains("<studio>八一电影制片厂</studio>"), "用户原有 tvshow.nfo 不应被规范化重写覆盖");
    }

    /**
     * 电影削刮本地优先：无 NFO 但同目录存在本地图片（poster.jpg/fanart.jpg）时同样走本地分支不回源 TMDB，
     * 元数据来源 local_nfo、文本字段缺失标记不完整，图片绑定本地文件。
     */
    @Test
    void shouldScrapeMovieFromLocalImagesWithoutNfo() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo parentFolder = createFolder(user.getId(), movieFolder.getId(), "Iron Man 2008");
        fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildFile("poster.jpg"), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildFile("fanart.jpg"), user.getId(), parentFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());

        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService, never()).autoMatch(any(), anyString(), anyString(), any());
        MediaItem item = queryItem(directory.getId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), item.getMatchStatus());
        MediaMetadata metadata = mediaMetadataMapper.selectById(item.getMetadataId());
        assertEquals("local_nfo", metadata.getSource());
        assertEquals("incomplete", metadata.getCompleteStatus());
        assertNull(metadata.getTitle());
        assertNull(metadata.getTmdbId());
        assertEquals("persisted", metadata.getPersistStatus());
        assertEquals(queryChildNode(parentFolder.getId(), "poster.jpg").getId(), metadata.getPosterFileNodeId());
        assertEquals(queryChildNode(parentFolder.getId(), "fanart.jpg").getId(), metadata.getBackdropFileNodeId());
    }

    /**
     * 电视剧削刮本地优先：无 tvshow.nfo 但剧文件夹存在本地图片（poster.jpg + season01-poster.jpg）时
     * 同样走本地分支不回源 TMDB，剧元数据 local_nfo 且不完整，季海报绑定本地文件。
     */
    @Test
    void shouldScrapeSeriesFromLocalImagesWithoutNfo() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo episodeFile = fileService.upload(buildFile("亮剑.S01E01.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        fileService.upload(buildFile("poster.jpg"), user.getId(), seriesFolder.getId(), null);
        fileService.upload(buildFile("season01-poster.jpg"), user.getId(), seriesFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        seedOldTvScanData(directory, "亮剑", null, 1, 1, episodeFile.getId());

        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService, never()).autoMatch(any(), anyString(), anyString(), any());
        verify(tmdbService, never()).getOrFetchSeason(any(), any(), any());
        MediaSeries series = mediaSeriesMapper.selectOne(
                new LambdaQueryWrapper<MediaSeries>().eq(MediaSeries::getUserId, user.getId()));
        assertEquals(MediaMatchStatus.MATCHED.getCode(), series.getMatchStatus());
        MediaMetadata seriesMetadata = mediaMetadataMapper.selectById(series.getMetadataId());
        assertEquals("local_nfo", seriesMetadata.getSource());
        assertEquals("incomplete", seriesMetadata.getCompleteStatus());
        assertNull(seriesMetadata.getTitle());
        assertEquals(queryChildNode(seriesFolder.getId(), "poster.jpg").getId(), seriesMetadata.getPosterFileNodeId());

        MediaSeason season = mediaSeasonMapper.selectOne(
                new LambdaQueryWrapper<MediaSeason>().eq(MediaSeason::getSeriesId, series.getId()));
        MediaMetadata seasonMetadata = mediaMetadataMapper.selectById(season.getMetadataId());
        assertEquals("local_nfo", seasonMetadata.getSource());
        assertEquals(queryChildNode(seriesFolder.getId(), "season01-poster.jpg").getId(),
                seasonMetadata.getPosterFileNodeId());
    }

    private FileNode queryChildNode(String parentId, String name) {
        return fileMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getParentId, parentId)
                .eq(FileNode::getName, name));
    }

    /**
     * 下载文件节点内容为 UTF-8 文本（验证写回/未写回用）。
     */
    private String downloadText(String fileNodeId, String userId) {
        try (InputStream in = fileService.download(fileNodeId, userId).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("测试下载文件失败", e);
        }
    }

    private MultipartFile buildTextFile(String name, String content) {
        return new MockMultipartFile("file", name, "application/xml", content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 扫描完成后会自动提交一次削刮（异步，测试事务内不可见、很快退出），
     * 显式削刮需等待其释放目录任务锁。
     */
    private void scrapeAwaitIdle(MediaDirectory directory, String userId, boolean force) {
        for (int i = 0; i < 50; i++) {
            try {
                mediaScrapeService.scrape(directory.getId(), userId, force);
                return;
            } catch (BusinessException e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        mediaScrapeService.scrape(directory.getId(), userId, force);
    }

    private MediaMetadata buildMetadata(String id, Long tmdbId) {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setId(id);
        metadata.setTmdbId(tmdbId);
        return metadata;
    }

    private MediaItem queryItem(String directoryId) {
        return mediaItemMapper.selectOne(
                new LambdaQueryWrapper<MediaItem>().eq(MediaItem::getDirectoryId, directoryId));
    }

    private MediaDirectory createDirectory(String userId, String folderNodeId, String mediaType) {
        MediaDirectory directory = new MediaDirectory();
        directory.setUserId(userId);
        directory.setName("测试媒体库");
        directory.setMediaType(mediaType);
        mediaDirectoryMapper.insert(directory);
        MediaDirectorySource source = new MediaDirectorySource();
        source.setDirectoryId(directory.getId());
        source.setFileNodeId(folderNodeId);
        mediaDirectorySourceMapper.insert(source);
        return directory;
    }

    /**
     * 电视库扫描已切换到新模型（issue #17），旧电视削刮路径（#20 替换前仍是旁路旧代码）
     * 的测试直接播种旧表数据，取代原先的「先扫描再削刮」准备方式。
     */
    private MediaSeries seedOldTvScanData(MediaDirectory directory, String seriesName, Integer releaseYear,
                                          Integer seasonNo, Integer episodeNo, String fileNodeId) {
        MediaSeries series = new MediaSeries();
        series.setUserId(directory.getUserId());
        series.setSeriesName(seriesName);
        series.setReleaseYear(releaseYear);
        series.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
        mediaSeriesMapper.insert(series);
        MediaSeason season = null;
        if (seasonNo != null) {
            season = new MediaSeason();
            season.setSeriesId(series.getId());
            season.setSeasonNo(seasonNo);
            mediaSeasonMapper.insert(season);
        }
        MediaItem item = new MediaItem();
        item.setUserId(directory.getUserId());
        item.setDirectoryId(directory.getId());
        item.setSourceId(mediaDirectorySourceMapper.selectOne(new LambdaQueryWrapper<MediaDirectorySource>()
                .eq(MediaDirectorySource::getDirectoryId, directory.getId())).getId());
        item.setFileNodeId(fileNodeId);
        item.setItemType(MediaItemType.EPISODE.getCode());
        item.setSeriesId(series.getId());
        item.setSeriesName(seriesName);
        item.setSeasonId(season == null ? null : season.getId());
        item.setSeasonNo(seasonNo);
        item.setEpisodeNo(episodeNo);
        item.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
        mediaItemMapper.insert(item);
        return series;
    }

    private MultipartFile buildFile(String name) {
        return new MockMultipartFile("file", name, "video/x-matroska", "video".getBytes());
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
