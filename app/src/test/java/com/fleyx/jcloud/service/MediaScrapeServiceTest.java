package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaScrapeStatus;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaDirectorySourceMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileDeleteDto;
import com.fleyx.jcloud.model.dto.MediaMatchUpdateDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.support.MediaMovieCascadeSupport;
import com.fleyx.jcloud.service.support.MediaTvCascadeSupport;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 媒体库削刮服务测试（新模型，issue #20）。
 * <p>
 * 覆盖验收标准：非强制削刮只处理未匹配/不完整非 manual 行；5 项完整性校验重算；
 * 剧集聚合语义（任一集缺简介即整部剧不完整）；本地 NFO 优先不请求 TMDB、写回不变；
 * 级联删除无孤儿（owner 指针反查）；集级手动修正接口移除、电影/剧集级修正置 manual 后
 * 自动削刮不再覆盖。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaScrapeServiceTest {

    @Autowired
    private FileService fileService;

    @Autowired
    private FileRecycleService fileRecycleService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private FileOperationService fileOperationService;

    @Autowired
    private MediaScrapeService mediaScrapeService;

    @Autowired
    private MediaItemService mediaItemService;

    @Autowired
    private MediaDirectoryMapper mediaDirectoryMapper;

    @Autowired
    private MediaDirectorySourceMapper mediaDirectorySourceMapper;

    @Autowired
    private MediaMovieMapper mediaMovieMapper;

    @Autowired
    private MediaMovieFileMapper mediaMovieFileMapper;

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
    private MediaMovieCascadeSupport mediaMovieCascadeSupport;

    @Autowired
    private MediaTvCascadeSupport mediaTvCascadeSupport;

    @Autowired
    private FileMapper fileMapper;

    @MockitoBean
    private TmdbService tmdbService;

    @TempDir
    Path tempDir;

    // ---------- 验收 1：处理范围 ----------

    /**
     * 非强制削刮只处理「未匹配或不完整」的非 manual 行：已完整行跳过，
     * 未匹配行与不完整行被重新削刮。
     */
    @Test
    void shouldScrapeOnlyUnmatchedOrIncompleteMovies() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo aFolder = createFolder(user.getId(), movieFolder.getId(), "Iron Man 2008");
        FileNodeVo aVideo = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), aFolder.getId(), null);
        FileNodeVo bFolder = createFolder(user.getId(), movieFolder.getId(), "Inception 2010");
        FileNodeVo bVideo = fileService.upload(buildFile("Inception.2010.1080p.mkv"), user.getId(), bFolder.getId(), null);
        FileNodeVo cFolder = createFolder(user.getId(), movieFolder.getId(), "Avatar 2009");
        FileNodeVo cVideo = fileService.upload(buildFile("Avatar.2009.1080p.mkv"), user.getId(), cFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movieA = seedMovie(directory, user.getId(), aFolder.getId(), "Iron Man", 2008, aVideo.getId());
        MediaMovie movieB = seedMovie(directory, user.getId(), bFolder.getId(), "Inception", 2010, bVideo.getId());
        movieB.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
        movieB.setMetadataComplete(true);
        mediaMovieMapper.updateById(movieB);
        // 已完整行的 NFO 产物视为齐备（工单 05 起 NFO 缺失会重新进入削刮候选）
        fileService.upload(buildTextFile("movie.nfo", "<movie></movie>"), user.getId(), bFolder.getId(), null);
        MediaMovie movieC = seedMovie(directory, user.getId(), cFolder.getId(), "Avatar", 2009, cVideo.getId());
        movieC.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
        movieC.setMetadataComplete(false);
        mediaMovieMapper.updateById(movieC);

        MediaMetadata metaA = fullMetadata(user.getId(), "movie", 1000L);
        MediaMetadata metaC = fullMetadata(user.getId(), "movie", 2000L);
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any()))
                .thenAnswer(inv -> "Iron Man".equals(inv.getArgument(2)) ? metaA
                        : "Avatar".equals(inv.getArgument(2)) ? metaC : null);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), eq("Inception"), any());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), mediaMovieMapper.selectById(movieA.getId()).getMatchStatus());
        assertTrue(mediaMovieMapper.selectById(movieA.getId()).getMetadataComplete());
        assertTrue(mediaMovieMapper.selectById(movieB.getId()).getMetadataComplete());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), mediaMovieMapper.selectById(movieC.getId()).getMatchStatus());
        assertTrue(mediaMovieMapper.selectById(movieC.getId()).getMetadataComplete());
    }

    /**
     * manual 行永不削刮（含 force）：自动削刮不覆盖手动修正结果。
     */
    @Test
    void shouldSkipManualMovieWhenScrapingWithForce() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());
        movie.setMetadataId("manualmeta001");
        movie.setMatchStatus(MediaMatchStatus.MANUAL.getCode());
        mediaMovieMapper.updateById(movie);

        scrapeAwaitIdle(directory, user.getId(), true);

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), anyString(), any());
        MediaMovie after = mediaMovieMapper.selectById(movie.getId());
        assertEquals("manualmeta001", after.getMetadataId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), after.getMatchStatus());
    }

    // ---------- 验收 2：5 项完整性校验重算 ----------

    /**
     * TMDB 削刮成功后按 5 项校验重算：标题/简介/海报/发行日期/评分齐备 → complete。
     */
    @Test
    void shouldMarkMovieCompleteWhenAllFiveItemsPresent() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata metadata = fullMetadata(user.getId(), "movie", 1000L);
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(metadata);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaMovie after = mediaMovieMapper.selectById(movie.getId());
        assertTrue(after.getMetadataComplete());
        MediaMetadata meta = mediaMetadataMapper.selectOne(new LambdaQueryWrapper<MediaMetadata>()
                .eq(MediaMetadata::getOwnerType, "movie")
                .eq(MediaMetadata::getOwnerId, movie.getId()));
        assertNotNull(meta);
        assertEquals("tmdb", meta.getSource());
        assertEquals("persisted", meta.getPersistStatus());
    }

    /**
     * 5 项中缺一项（简介为空）→ 不完整。
     */
    @Test
    void shouldMarkMovieIncompleteWhenFieldMissing() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata metadata = fullMetadata(user.getId(), "movie", 1000L);
        metadata.setOverview(null);
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(metadata);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        assertFalse(mediaMovieMapper.selectById(movie.getId()).getMetadataComplete());
    }

    // ---------- 验收 3：剧集聚合语义 ----------

    /**
     * 剧自身 + 全部季、集元数据完整 → 剧完整。
     */
    @Test
    void shouldMarkSeriesCompleteWhenAllSeasonsEpisodesComplete() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo epFile = fileService.upload(buildFile("亮剑.S01E01.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        FileNodeVo ep2File = fileService.upload(buildFile("亮剑.S01E02.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        MediaSeries series = seedSeries(directory, user.getId(), seriesFolder.getId(), "亮剑", null);
        MediaSeason season = seedSeason(series.getId(), seasonFolder.getId(), 1);
        MediaEpisode ep1 = seedEpisode(series.getId(), season.getId(), 1, epFile.getId());
        MediaEpisode ep2 = seedEpisode(series.getId(), season.getId(), 2, ep2File.getId());

        MediaMetadata seriesMeta = fullMetadata(user.getId(), "series", 2000L);
        MediaMetadata seasonMeta = fullMetadata(user.getId(), "season", null);
        MediaMetadata ep1Meta = fullMetadata(user.getId(), "episode", null);
        MediaMetadata ep2Meta = fullMetadata(user.getId(), "episode", null);
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("tv"), eq("亮剑"), isNull())).thenReturn(seriesMeta);
        when(tmdbService.fetchSeasonV2(eq(user.getId()), eq(2000L), eq(1)))
                .thenReturn(new TmdbService.SeasonFetchV2(seasonMeta, Map.of(1, ep1Meta, 2, ep2Meta)));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaSeries after = mediaSeriesMapper.selectById(series.getId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), after.getMatchStatus());
        assertTrue(after.getMetadataComplete());
        assertNotNull(mediaMetadataMapper.selectOne(owner("season", season.getId())));
        assertNotNull(mediaMetadataMapper.selectOne(owner("episode", ep1.getId())));
        assertNotNull(mediaMetadataMapper.selectOne(owner("episode", ep2.getId())));
    }

    /**
     * 任一集缺简介 → 整部剧不完整（聚合语义）。
     */
    @Test
    void shouldMarkSeriesIncompleteWhenAnyEpisodeIncomplete() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo epFile = fileService.upload(buildFile("亮剑.S01E01.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        FileNodeVo ep2File = fileService.upload(buildFile("亮剑.S01E02.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        MediaSeries series = seedSeries(directory, user.getId(), seriesFolder.getId(), "亮剑", null);
        MediaSeason season = seedSeason(series.getId(), seasonFolder.getId(), 1);
        seedEpisode(series.getId(), season.getId(), 1, epFile.getId());
        seedEpisode(series.getId(), season.getId(), 2, ep2File.getId());

        MediaMetadata seriesMeta = fullMetadata(user.getId(), "series", 2000L);
        MediaMetadata seasonMeta = fullMetadata(user.getId(), "season", null);
        MediaMetadata ep1Meta = fullMetadata(user.getId(), "episode", null);
        MediaMetadata ep2Meta = fullMetadata(user.getId(), "episode", null);
        ep2Meta.setOverview(null);
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("tv"), eq("亮剑"), isNull())).thenReturn(seriesMeta);
        when(tmdbService.fetchSeasonV2(eq(user.getId()), eq(2000L), eq(1)))
                .thenReturn(new TmdbService.SeasonFetchV2(seasonMeta, Map.of(1, ep1Meta, 2, ep2Meta)));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        assertFalse(mediaSeriesMapper.selectById(series.getId()).getMetadataComplete());
    }

    // ---------- 验收 4：本地 NFO 优先、不请求 TMDB、写回不变 ----------

    /**
     * 电影本地优先（反转，工单 05）：同目录同名 .nfo 存在时本地字段优先、字段齐备时不请求 TMDB 补全
     * （方法内短路）、不重新自动匹配，合并结果整体重写回 NFO（写回 movie.nfo，
     * 用户自定义未知字段如 studio 不保留）。
     */
    @Test
    void shouldScrapeMovieFromLocalNfoWithoutTmdb() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo parentFolder = createFolder(user.getId(), movieFolder.getId(), "Iron Man 2008");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), parentFolder.getId(), null);
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
        MediaMovie movie = seedMovie(directory, user.getId(), parentFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata remote = fullMetadata(user.getId(), "movie", 1726L);
        remote.setTitle("钢铁侠（TMDB 补全标题）");
        remote.setOverview("远端简介");
        when(tmdbService.fetchDetailV2(eq(user.getId()), eq(1726L), eq("movie"))).thenReturn(remote);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), anyString(), any());
        // 本地字段已齐备（完整）→ 无需 TMDB 补全网络请求（方法内短路）
        verify(tmdbService, never()).fetchDetailV2(any(), any(), any());
        MediaMovie after = mediaMovieMapper.selectById(movie.getId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), after.getMatchStatus());
        assertTrue(after.getMetadataComplete());
        MediaMetadata metadata = mediaMetadataMapper.selectOne(owner("movie", movie.getId()));
        assertEquals("local_nfo", metadata.getSource());
        assertEquals(1726L, metadata.getTmdbId());
        // 本地字段优先：NFO 已有 title/overview 不被远端覆盖
        assertEquals("钢铁侠", metadata.getTitle());
        assertEquals("托尼·斯塔克打造钢铁战衣", metadata.getOverview());
        assertEquals("2008-04-30", metadata.getReleaseDate());
        assertEquals("persisted", metadata.getPersistStatus());
        assertEquals(queryChildNode(parentFolder.getId(), "poster.jpg").getId(), metadata.getPosterFileNodeId());
        assertEquals(queryChildNode(parentFolder.getId(), "fanart.jpg").getId(), metadata.getBackdropFileNodeId());
        // 已整体重写：写回 movie.nfo 含本地字段，用户自定义 <studio> 不保留
        FileNode nfoNode = queryChildNode(parentFolder.getId(), "movie.nfo");
        assertNotNull(nfoNode);
        String nfoContent = downloadText(nfoNode.getId(), user.getId());
        assertTrue(nfoContent.contains("<plot>托尼·斯塔克打造钢铁战衣</plot>"), "movie.nfo 应整体重写含本地简介");
        assertTrue(nfoContent.contains("<tmdbid>1726</tmdbid>"));
        assertFalse(nfoContent.contains("studio"), "用户自定义未知字段不保留");
    }

    /**
     * 电影本地优先：目录仅有 movie.nfo（无同名 .nfo）时同样识别成功，元数据 local_nfo、不请求 TMDB。
     */
    @Test
    void shouldScrapeMovieFromMovieNfoOnly() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo parentFolder = createFolder(user.getId(), movieFolder.getId(), "Iron Man 2008");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildTextFile("movie.nfo", """
                <movie>
                  <tmdbid>1726</tmdbid>
                  <title>钢铁侠</title>
                  <plot>托尼·斯塔克打造钢铁战衣</plot>
                  <premiered>2008-04-30</premiered>
                  <rating>7.6</rating>
                </movie>
                """), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildFile("poster.jpg"), user.getId(), parentFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), parentFolder.getId(), "Iron Man", 2008, videoFile.getId());

        when(tmdbService.fetchDetailV2(eq(user.getId()), eq(1726L), eq("movie")))
                .thenReturn(fullMetadata(user.getId(), "movie", 1726L));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), anyString(), any());
        MediaMovie after = mediaMovieMapper.selectById(movie.getId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), after.getMatchStatus());
        assertTrue(after.getMetadataComplete());
        MediaMetadata metadata = mediaMetadataMapper.selectOne(owner("movie", movie.getId()));
        assertEquals("local_nfo", metadata.getSource());
        assertEquals(1726L, metadata.getTmdbId());
        assertEquals("钢铁侠", metadata.getTitle());
    }

    /**
     * 电影本地优先：movie.nfo 与同名 .nfo 并存时以 movie.nfo 为准（识别优先级）；
     * movie.nfo 无 tmdbid 时经自动匹配补全文本，但 NFO 已有字段不被覆盖（工单 05 本地优先）。
     */
    @Test
    void shouldPreferMovieNfoOverVideoNamedNfo() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo parentFolder = createFolder(user.getId(), movieFolder.getId(), "Iron Man 2008");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildTextFile("movie.nfo", """
                <movie>
                  <title>movie.nfo 优先</title>
                  <plot>movie.nfo 内容</plot>
                </movie>
                """), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildTextFile("Iron.Man.2008.1080p.nfo", """
                <movie>
                  <title>同名 nfo 内容</title>
                  <plot>同名 .nfo 内容</plot>
                </movie>
                """), user.getId(), parentFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), parentFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata remote = fullMetadata(user.getId(), "movie", 1726L);
        remote.setTitle("TMDB 匹配标题");
        remote.setOverview("TMDB 匹配简介");
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(remote);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService).autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any());
        MediaMetadata metadata = mediaMetadataMapper.selectOne(owner("movie", movie.getId()));
        assertEquals("local_nfo", metadata.getSource());
        // 本地字段优先：NFO 已有 title/overview 不被匹配结果覆盖
        assertEquals("movie.nfo 优先", metadata.getTitle());
        assertEquals("movie.nfo 内容", metadata.getOverview());
        assertEquals(1726L, metadata.getTmdbId());
    }

    /**
     * 电视剧本地优先（反转，工单 05）：tvshow.nfo + 季海报 + 集 nfo/剧照全本地绑定，不重新自动匹配；
     * 剧级字段齐备不拉详情补全（方法内短路），季/集本地字段优先、缺失字段由 TMDB 季数据补全
     * （fetchSeasonV2），补全后整剧完整；tvshow.nfo 与集 nfo 被整体重写（用户自定义字段不保留）。
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
        MediaSeries series = seedSeries(directory, user.getId(), seriesFolder.getId(), "亮剑", null);
        MediaSeason season = seedSeason(series.getId(), seasonFolder.getId(), 1);
        MediaEpisode episode = seedEpisode(series.getId(), season.getId(), 1, episodeFile.getId());

        MediaMetadata seriesRemote = fullMetadata(user.getId(), "series", 2000L);
        seriesRemote.setTitle("亮剑（TMDB 补全标题）");
        MediaMetadata seasonRemote = fullMetadata(user.getId(), "season", null);
        MediaMetadata episodeRemote = fullMetadata(user.getId(), "episode", null);
        episodeRemote.setTitle("远端集标题");
        when(tmdbService.fetchDetailV2(eq(user.getId()), eq(2000L), eq("tv"))).thenReturn(seriesRemote);
        when(tmdbService.fetchSeasonV2(eq(user.getId()), eq(2000L), eq(1)))
                .thenReturn(new TmdbService.SeasonFetchV2(seasonRemote, Map.of(1, episodeRemote)));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), anyString(), any());
        // 剧级字段已齐备（完整）→ 无需剧级详情补全网络请求；季/集缺失字段由 TMDB 季数据补全
        verify(tmdbService, never()).fetchDetailV2(any(), any(), any());
        verify(tmdbService).fetchSeasonV2(user.getId(), 2000L, 1);
        MediaSeries after = mediaSeriesMapper.selectById(series.getId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), after.getMatchStatus());
        MediaMetadata seriesMetadata = mediaMetadataMapper.selectOne(owner("series", series.getId()));
        assertEquals("local_nfo", seriesMetadata.getSource());
        assertEquals(2000L, seriesMetadata.getTmdbId());
        assertEquals("亮剑", seriesMetadata.getTitle());
        assertEquals("persisted", seriesMetadata.getPersistStatus());
        assertEquals(queryChildNode(seriesFolder.getId(), "poster.jpg").getId(), seriesMetadata.getPosterFileNodeId());
        MediaMetadata seasonMetadata = mediaMetadataMapper.selectOne(owner("season", season.getId()));
        assertEquals("local_nfo", seasonMetadata.getSource());
        assertEquals(queryChildNode(seriesFolder.getId(), "season01-poster.jpg").getId(),
                seasonMetadata.getPosterFileNodeId());
        // 季/集缺失字段由 TMDB 补全：releaseDate/voteAverage 不再为空
        assertEquals("2008-04-30", seasonMetadata.getReleaseDate());
        MediaMetadata episodeMetadata = mediaMetadataMapper.selectOne(owner("episode", episode.getId()));
        assertEquals("local_nfo", episodeMetadata.getSource());
        // 本地字段优先：集 nfo 已有 title 不被远端覆盖
        assertEquals("苍云岭之战", episodeMetadata.getTitle());
        assertEquals(queryChildNode(seasonFolder.getId(), "亮剑.S01E01.1080p-thumb.jpg").getId(),
                episodeMetadata.getPosterFileNodeId());
        // 聚合语义：季/集补全后整剧完整
        assertTrue(after.getMetadataComplete());
        // 已整体重写：tvshow.nfo 规范化重写（用户自定义 <studio> 不保留），集 nfo 写回
        String tvshowNfo = downloadText(queryChildNode(seriesFolder.getId(), "tvshow.nfo").getId(), user.getId());
        assertTrue(tvshowNfo.contains("<plot>李云龙抗战传奇</plot>"), "tvshow.nfo 应整体重写含本地简介");
        assertFalse(tvshowNfo.contains("studio"), "用户自定义未知字段不保留");
        assertNotNull(queryChildNode(seasonFolder.getId(), "亮剑.S01E01.1080p.nfo"));
    }

    /**
     * 电影本地优先（反转，工单 05）：无 NFO 但存在本地图片（poster.jpg/fanart.jpg）时图片沿用本地，
     * 文本字段由 TMDB 自动匹配补全并写回 movie.nfo。
     */
    @Test
    void shouldScrapeMovieFromLocalImagesWithoutNfo() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo parentFolder = createFolder(user.getId(), movieFolder.getId(), "Iron Man 2008");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildFile("poster.jpg"), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildFile("fanart.jpg"), user.getId(), parentFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), parentFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata remote = fullMetadata(user.getId(), "movie", 1000L);
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(remote);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService).autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any());
        MediaMovie after = mediaMovieMapper.selectById(movie.getId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), after.getMatchStatus());
        assertTrue(after.getMetadataComplete());
        MediaMetadata metadata = mediaMetadataMapper.selectOne(owner("movie", movie.getId()));
        assertEquals("local_nfo", metadata.getSource());
        // 文本字段由 TMDB 补全，图片沿用本地
        assertEquals("钢铁侠", metadata.getTitle());
        assertEquals(1000L, metadata.getTmdbId());
        assertEquals("persisted", metadata.getPersistStatus());
        assertEquals(queryChildNode(parentFolder.getId(), "poster.jpg").getId(), metadata.getPosterFileNodeId());
        assertEquals(queryChildNode(parentFolder.getId(), "fanart.jpg").getId(), metadata.getBackdropFileNodeId());
        assertNotNull(queryChildNode(parentFolder.getId(), "movie.nfo"));
    }

    // ---------- 验收 7：本地图片命名兼容（ADR 0022） ----------

    /**
     * 电影本地优先（回归用例）：目录仅有 folder.jpg/backdrop.jpg（Emby 命名，无 NFO）时
     * 同样本地优先、不请求 TMDB，海报与背景绑定这两张图。
     */
    @Test
    void shouldScrapeMovieFromFolderArtworkNames() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo parentFolder = createFolder(user.getId(), movieFolder.getId(), "Iron Man 2008");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildFile("folder.jpg"), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildFile("backdrop.jpg"), user.getId(), parentFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), parentFolder.getId(), "Iron Man", 2008, videoFile.getId());

        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any()))
                .thenReturn(fullMetadata(user.getId(), "movie", 1000L));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaMetadata metadata = mediaMetadataMapper.selectOne(owner("movie", movie.getId()));
        assertEquals("local_nfo", metadata.getSource());
        assertEquals(queryChildNode(parentFolder.getId(), "folder.jpg").getId(), metadata.getPosterFileNodeId());
        assertEquals(queryChildNode(parentFolder.getId(), "backdrop.jpg").getId(), metadata.getBackdropFileNodeId());
    }

    /**
     * 电视剧本地优先（回归用例）：剧文件夹仅有 folder.jpg/backdrop.jpg（无 NFO、无季海报）时
     * 本地优先、不请求 TMDB，剧级海报与背景绑定这两张图。
     */
    @Test
    void shouldScrapeSeriesFromFolderArtworkNames() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo episodeFile = fileService.upload(buildFile("亮剑.S01E01.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        fileService.upload(buildFile("folder.jpg"), user.getId(), seriesFolder.getId(), null);
        fileService.upload(buildFile("backdrop.jpg"), user.getId(), seriesFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        MediaSeries series = seedSeries(directory, user.getId(), seriesFolder.getId(), "亮剑", null);
        seedEpisode(series.getId(), seedSeason(series.getId(), seasonFolder.getId(), 1).getId(), 1, episodeFile.getId());

        when(tmdbService.autoMatchV2(eq(user.getId()), eq("tv"), eq("亮剑"), isNull()))
                .thenReturn(fullMetadata(user.getId(), "series", 2000L));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaMetadata seriesMetadata = mediaMetadataMapper.selectOne(owner("series", series.getId()));
        assertEquals("local_nfo", seriesMetadata.getSource());
        assertEquals(queryChildNode(seriesFolder.getId(), "folder.jpg").getId(), seriesMetadata.getPosterFileNodeId());
        assertEquals(queryChildNode(seriesFolder.getId(), "backdrop.jpg").getId(), seriesMetadata.getBackdropFileNodeId());
    }

    /**
     * 优先级：电影目录 poster.jpg 与 folder.jpg 并存时链首 folder.jpg 胜出（结果可预期）。
     */
    @Test
    void shouldPreferFolderJpgWhenMultiplePosterNamesExist() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo parentFolder = createFolder(user.getId(), movieFolder.getId(), "Iron Man 2008");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildFile("poster.jpg"), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildFile("folder.jpg"), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildFile("fanart.jpg"), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildFile("backdrop.jpg"), user.getId(), parentFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), parentFolder.getId(), "Iron Man", 2008, videoFile.getId());

        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any()))
                .thenReturn(fullMetadata(user.getId(), "movie", 1000L));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaMetadata metadata = mediaMetadataMapper.selectOne(owner("movie", movie.getId()));
        assertEquals(queryChildNode(parentFolder.getId(), "folder.jpg").getId(), metadata.getPosterFileNodeId());
        assertEquals(queryChildNode(parentFolder.getId(), "backdrop.jpg").getId(), metadata.getBackdropFileNodeId());
    }

    /**
     * 别名识别：电影目录仅 cover.jpg、default.jpg 或 movie.jpg 时仍可识别海报（链序在后）。
     */
    @Test
    void shouldRecognizeMoviePosterAliases() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo coverFolder = createFolder(user.getId(), movieFolder.getId(), "Cover 2008");
        FileNodeVo coverVideo = fileService.upload(buildFile("Cover.2008.mkv"), user.getId(), coverFolder.getId(), null);
        fileService.upload(buildFile("cover.jpg"), user.getId(), coverFolder.getId(), null);
        FileNodeVo defaultFolder = createFolder(user.getId(), movieFolder.getId(), "Default 2010");
        FileNodeVo defaultVideo = fileService.upload(buildFile("Default.2010.mkv"), user.getId(), defaultFolder.getId(), null);
        fileService.upload(buildFile("default.jpg"), user.getId(), defaultFolder.getId(), null);
        FileNodeVo movieJpgFolder = createFolder(user.getId(), movieFolder.getId(), "MovieJpg 2009");
        FileNodeVo movieJpgVideo = fileService.upload(buildFile("MovieJpg.2009.mkv"), user.getId(), movieJpgFolder.getId(), null);
        fileService.upload(buildFile("movie.jpg"), user.getId(), movieJpgFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie coverMovie = seedMovie(directory, user.getId(), coverFolder.getId(), "Cover", 2008, coverVideo.getId());
        MediaMovie defaultMovie = seedMovie(directory, user.getId(), defaultFolder.getId(), "Default", 2010, defaultVideo.getId());
        MediaMovie movieJpgMovie = seedMovie(directory, user.getId(), movieJpgFolder.getId(), "MovieJpg", 2009, movieJpgVideo.getId());

        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any()))
                .thenReturn(fullMetadata(user.getId(), "movie", 1000L));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaMetadata coverMeta = mediaMetadataMapper.selectOne(owner("movie", coverMovie.getId()));
        assertEquals("local_nfo", coverMeta.getSource());
        assertEquals(queryChildNode(coverFolder.getId(), "cover.jpg").getId(), coverMeta.getPosterFileNodeId());
        MediaMetadata defaultMeta = mediaMetadataMapper.selectOne(owner("movie", defaultMovie.getId()));
        assertEquals(queryChildNode(defaultFolder.getId(), "default.jpg").getId(), defaultMeta.getPosterFileNodeId());
        MediaMetadata movieJpgMeta = mediaMetadataMapper.selectOne(owner("movie", movieJpgMovie.getId()));
        assertEquals(queryChildNode(movieJpgFolder.getId(), "movie.jpg").getId(), movieJpgMeta.getPosterFileNodeId());
    }

    /**
     * 别名识别：背景目录仅 background.jpg 或 art.jpg 时仍可识别（链序在后）。
     */
    @Test
    void shouldRecognizeBackdropAliases() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo bgFolder = createFolder(user.getId(), movieFolder.getId(), "Bg 2008");
        FileNodeVo bgVideo = fileService.upload(buildFile("Bg.2008.mkv"), user.getId(), bgFolder.getId(), null);
        fileService.upload(buildFile("background.jpg"), user.getId(), bgFolder.getId(), null);
        FileNodeVo artFolder = createFolder(user.getId(), movieFolder.getId(), "Art 2009");
        FileNodeVo artVideo = fileService.upload(buildFile("Art.2009.mkv"), user.getId(), artFolder.getId(), null);
        fileService.upload(buildFile("art.jpg"), user.getId(), artFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie bgMovie = seedMovie(directory, user.getId(), bgFolder.getId(), "Bg", 2008, bgVideo.getId());
        MediaMovie artMovie = seedMovie(directory, user.getId(), artFolder.getId(), "Art", 2009, artVideo.getId());

        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any()))
                .thenReturn(fullMetadata(user.getId(), "movie", 1000L));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaMetadata bgMeta = mediaMetadataMapper.selectOne(owner("movie", bgMovie.getId()));
        assertEquals("local_nfo", bgMeta.getSource());
        assertEquals(queryChildNode(bgFolder.getId(), "background.jpg").getId(), bgMeta.getBackdropFileNodeId());
        MediaMetadata artMeta = mediaMetadataMapper.selectOne(owner("movie", artMovie.getId()));
        assertEquals(queryChildNode(artFolder.getId(), "art.jpg").getId(), artMeta.getBackdropFileNodeId());
    }

    /**
     * 别名识别：剧文件夹仅 show.jpg 时仍可识别剧集海报（链序在后）。
     */
    @Test
    void shouldRecognizeSeriesShowJpgAlias() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo episodeFile = fileService.upload(buildFile("亮剑.S01E01.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        fileService.upload(buildFile("show.jpg"), user.getId(), seriesFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        MediaSeries series = seedSeries(directory, user.getId(), seriesFolder.getId(), "亮剑", null);
        seedEpisode(series.getId(), seedSeason(series.getId(), seasonFolder.getId(), 1).getId(), 1, episodeFile.getId());

        when(tmdbService.autoMatchV2(eq(user.getId()), eq("tv"), eq("亮剑"), isNull()))
                .thenReturn(fullMetadata(user.getId(), "series", 2000L));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaMetadata seriesMetadata = mediaMetadataMapper.selectOne(owner("series", series.getId()));
        assertEquals("local_nfo", seriesMetadata.getSource());
        assertEquals(queryChildNode(seriesFolder.getId(), "show.jpg").getId(), seriesMetadata.getPosterFileNodeId());
    }

    // ---------- 验收 5：级联删除无孤儿（owner 指针反查） ----------

    /**
     * 删除电影时其元数据行级联删除，owner 指针反查为空。
     */
    @Test
    void shouldCascadeDeleteMovieMetadata() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, null);
        MediaMetadata metadata = new MediaMetadata();
        metadata.setUserId(user.getId());
        metadata.setOwnerType("movie");
        metadata.setOwnerId(movie.getId());
        metadata.setSource("tmdb");
        metadata.setTitle("钢铁侠");
        mediaMetadataMapper.insert(metadata);

        mediaMovieCascadeSupport.deleteMoviesCascade(List.of(movie.getId()));

        assertNull(mediaMovieMapper.selectById(movie.getId()));
        assertNull(mediaMetadataMapper.selectOne(owner("movie", movie.getId())));
        assertEquals(0, mediaMetadataMapper.selectCount(new LambdaQueryWrapper<MediaMetadata>()
                .eq(MediaMetadata::getUserId, user.getId())));
    }

    /**
     * 删除剧时剧/季/集元数据行全部级联删除，owner 指针反查为空。
     */
    @Test
    void shouldCascadeDeleteSeriesMetadata() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        MediaSeries series = seedSeries(directory, user.getId(), seriesFolder.getId(), "亮剑", null);
        MediaSeason season = seedSeason(series.getId(), seasonFolder.getId(), 1);
        MediaEpisode episode = seedEpisode(series.getId(), season.getId(), 1, null);
        seedMetadata(user.getId(), "series", series.getId());
        seedMetadata(user.getId(), "season", season.getId());
        seedMetadata(user.getId(), "episode", episode.getId());

        mediaTvCascadeSupport.deleteSeriesCascade(List.of(series.getId()));

        assertNull(mediaSeriesMapper.selectById(series.getId()));
        assertNull(mediaSeasonMapper.selectById(season.getId()));
        assertNull(mediaEpisodeMapper.selectById(episode.getId()));
        assertEquals(0, mediaMetadataMapper.selectCount(new LambdaQueryWrapper<MediaMetadata>()
                .eq(MediaMetadata::getUserId, user.getId())));
    }

    // ---------- 验收 6：集级手动修正移除 + manual 保护 ----------

    /**
     * 集级手动修正接口已移除：对集 ID 调用手动修正抛出业务异常。
     */
    @Test
    void shouldRejectEpisodeManualCorrection() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        MediaSeries series = seedSeries(directory, user.getId(), seriesFolder.getId(), "亮剑", null);
        MediaSeason season = seedSeason(series.getId(), seasonFolder.getId(), 1);
        MediaEpisode episode = seedEpisode(series.getId(), season.getId(), 1, null);

        MediaMatchUpdateDto dto = new MediaMatchUpdateDto();
        dto.setTmdbId(1000L);
        dto.setMediaType("tv");
        assertThrows(BusinessException.class,
                () -> mediaItemService.updateMatch(episode.getId(), dto, user.getId()));
        verify(tmdbService, never()).fetchDetailV2(any(), any(), any());
    }

    /**
     * 电影级手动修正置 manual 后，强制削刮不再覆盖（manual 永不覆盖语义保留）。
     */
    @Test
    void shouldPreserveManualMovieMatchOnForceScrape() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata manualMeta = fullMetadata(user.getId(), "movie", 1000L);
        when(tmdbService.fetchDetailV2(eq(user.getId()), eq(1000L), eq("movie"))).thenReturn(manualMeta);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        MediaMatchUpdateDto dto = new MediaMatchUpdateDto();
        dto.setTmdbId(1000L);
        dto.setMediaType("movie");
        mediaItemService.updateMatch(movie.getId(), dto, user.getId());

        MediaMovie manual = mediaMovieMapper.selectById(movie.getId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), manual.getMatchStatus());
        assertNotNull(manual.getMetadataId());

        scrapeAwaitIdle(directory, user.getId(), true);

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), anyString(), any());
        MediaMovie after = mediaMovieMapper.selectById(movie.getId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), after.getMatchStatus());
        assertEquals(manual.getMetadataId(), after.getMetadataId());
    }

    /**
     * 剧集级手动修正置 manual 后，强制削刮不再覆盖。
     */
    @Test
    void shouldPreserveManualSeriesMatchOnForceScrape() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo epFile = fileService.upload(buildFile("亮剑.S01E01.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        MediaSeries series = seedSeries(directory, user.getId(), seriesFolder.getId(), "亮剑", null);
        MediaSeason season = seedSeason(series.getId(), seasonFolder.getId(), 1);
        seedEpisode(series.getId(), season.getId(), 1, epFile.getId());

        MediaMetadata manualMeta = fullMetadata(user.getId(), "series", 2000L);
        MediaMetadata seasonMeta = fullMetadata(user.getId(), "season", null);
        MediaMetadata epMeta = fullMetadata(user.getId(), "episode", null);
        when(tmdbService.fetchDetailV2(eq(user.getId()), eq(2000L), eq("tv"))).thenReturn(manualMeta);
        when(tmdbService.fetchSeasonV2(eq(user.getId()), eq(2000L), eq(1)))
                .thenReturn(new TmdbService.SeasonFetchV2(seasonMeta, Map.of(1, epMeta)));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        MediaMatchUpdateDto dto = new MediaMatchUpdateDto();
        dto.setTmdbId(2000L);
        dto.setMediaType("tv");
        // issue #21：剧集级手动修正统一按行 ID
        mediaItemService.updateMatch(series.getId(), dto, user.getId());

        MediaSeries manual = mediaSeriesMapper.selectById(series.getId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), manual.getMatchStatus());
        assertNotNull(manual.getMetadataId());

        scrapeAwaitIdle(directory, user.getId(), true);

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), anyString(), any());
        MediaSeries after = mediaSeriesMapper.selectById(series.getId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), after.getMatchStatus());
        assertEquals(manual.getMetadataId(), after.getMetadataId());
    }

    // ---------- 工单 03：削刮图片产物缺失补回 ----------

    /**
     * TMDB 削刮成功后物理删除图片产物（含 movie.nfo，本地 NFO 残留会走本地优先路径属工单 05）
     * → 置不完整 → 非强制削刮复用已有元数据行重建图片，metadataId 不变、不重新搜索 TMDB。
     */
    @Test
    void shouldRefillMoviePosterAfterDeletion() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata metadata = fullMetadata(user.getId(), "movie", 1000L);
        metadata.setRawJson("{\"poster_path\":\"/p.jpg\",\"backdrop_path\":\"/b.jpg\"}");
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(metadata);
        when(tmdbService.downloadArtwork("/p.jpg", "poster")).thenReturn(new byte[]{1, 2, 3});
        when(tmdbService.downloadArtwork("/b.jpg", "backdrop")).thenReturn(new byte[]{4, 5});

        scrapeAwaitIdle(directory, user.getId(), false);
        String metadataId = mediaMovieMapper.selectById(movie.getId()).getMetadataId();
        assertNotNull(metadataId);
        assertNotNull(queryChildNode(movieFolder.getId(), "folder.jpg"));

        // 物理删除图片产物与 movie.nfo（回收站即物理删行，工单 02 完整性按 FileNode 存在性判定）
        for (String name : List.of("folder.jpg", "backdrop.jpg", "movie.nfo")) {
            FileNode node = queryChildNode(movieFolder.getId(), name);
            if (node != null) {
                fileMapper.deleteById(node.getId());
            }
        }
        MediaMovie incomplete = new MediaMovie();
        incomplete.setId(movie.getId());
        incomplete.setMetadataComplete(false);
        mediaMovieMapper.updateById(incomplete);

        clearInvocations(tmdbService);
        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), anyString(), any());
        MediaMovie after = mediaMovieMapper.selectById(movie.getId());
        assertEquals(metadataId, after.getMetadataId());
        assertTrue(after.getMetadataComplete());
        MediaMetadata meta = mediaMetadataMapper.selectById(metadataId);
        assertEquals("tmdb", meta.getSource());
        assertEquals(queryChildNode(movieFolder.getId(), "folder.jpg").getId(), meta.getPosterFileNodeId());
        assertEquals(queryChildNode(movieFolder.getId(), "backdrop.jpg").getId(), meta.getBackdropFileNodeId());
    }

    /**
     * 剧集版补回：TMDB 削刮成功后物理删除剧级/季级本地产物与集剧照 → 置不完整 → 非强制削刮
     * 复用剧级元数据行重建季海报与集剧照，metadataId 不变、不重新搜索 TMDB。
     */
    @Test
    void shouldRefillSeriesArtifactsAfterDeletion() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo epFile = fileService.upload(buildFile("亮剑.S01E01.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        MediaSeries series = seedSeries(directory, user.getId(), seriesFolder.getId(), "亮剑", null);
        MediaSeason season = seedSeason(series.getId(), seasonFolder.getId(), 1);
        MediaEpisode episode = seedEpisode(series.getId(), season.getId(), 1, epFile.getId());

        MediaMetadata seriesMeta = fullMetadata(user.getId(), "series", 2000L);
        MediaMetadata seasonMeta = fullMetadata(user.getId(), "season", null);
        MediaMetadata epMeta = fullMetadata(user.getId(), "episode", null);
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("tv"), eq("亮剑"), isNull())).thenReturn(seriesMeta);
        when(tmdbService.fetchSeasonV2(eq(user.getId()), eq(2000L), eq(1)))
                .thenReturn(new TmdbService.SeasonFetchV2(seasonMeta, Map.of(1, epMeta)));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);
        String metadataId = mediaSeriesMapper.selectById(series.getId()).getMetadataId();
        assertNotNull(metadataId);
        assertNotNull(queryChildNode(seriesFolder.getId(), "season01-poster.jpg"));
        assertNotNull(queryChildNode(seasonFolder.getId(), "亮剑.S01E01.1080p-thumb.jpg"));

        // 物理删除剧级本地产物与季海报/集剧照（tvshow.nfo/剧级图残留会走本地优先路径属工单 05）
        for (String name : List.of("tvshow.nfo", "folder.jpg", "backdrop.jpg", "season01-poster.jpg")) {
            FileNode node = queryChildNode(seriesFolder.getId(), name);
            if (node != null) {
                fileMapper.deleteById(node.getId());
            }
        }
        FileNode thumb = queryChildNode(seasonFolder.getId(), "亮剑.S01E01.1080p-thumb.jpg");
        if (thumb != null) {
            fileMapper.deleteById(thumb.getId());
        }
        MediaSeries incomplete = new MediaSeries();
        incomplete.setId(series.getId());
        incomplete.setMetadataComplete(false);
        mediaSeriesMapper.updateById(incomplete);

        clearInvocations(tmdbService);
        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), anyString(), any());
        MediaSeries after = mediaSeriesMapper.selectById(series.getId());
        assertEquals(metadataId, after.getMetadataId());
        assertTrue(after.getMetadataComplete());
        assertNotNull(queryChildNode(seriesFolder.getId(), "season01-poster.jpg"));
        assertNotNull(queryChildNode(seasonFolder.getId(), "亮剑.S01E01.1080p-thumb.jpg"));
    }

    /**
     * manual 行删图后补回：复用已有元数据行重建图片，matchStatus=manual 与 metadataId 不变、不重新匹配。
     */
    @Test
    void shouldRefillManualMovieArtifactsWithoutChangingMatch() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata manualMeta = fullMetadata(user.getId(), "movie", 1000L);
        when(tmdbService.fetchDetailV2(eq(user.getId()), eq(1000L), eq("movie"))).thenReturn(manualMeta);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        MediaMatchUpdateDto dto = new MediaMatchUpdateDto();
        dto.setTmdbId(1000L);
        dto.setMediaType("movie");
        mediaItemService.updateMatch(movie.getId(), dto, user.getId());

        MediaMovie manual = mediaMovieMapper.selectById(movie.getId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), manual.getMatchStatus());
        String metadataId = manual.getMetadataId();
        assertNotNull(metadataId);
        assertNotNull(queryChildNode(movieFolder.getId(), "folder.jpg"));

        // 物理删除图片产物 → 置不完整（模拟扫描末尾重算）→ 非强制削刮补回
        for (String name : List.of("folder.jpg", "backdrop.jpg")) {
            FileNode node = queryChildNode(movieFolder.getId(), name);
            if (node != null) {
                fileMapper.deleteById(node.getId());
            }
        }
        MediaMovie incomplete = new MediaMovie();
        incomplete.setId(movie.getId());
        incomplete.setMetadataComplete(false);
        mediaMovieMapper.updateById(incomplete);

        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), anyString(), any());
        MediaMovie after = mediaMovieMapper.selectById(movie.getId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), after.getMatchStatus());
        assertEquals(metadataId, after.getMetadataId());
        assertTrue(after.getMetadataComplete());
        assertNotNull(queryChildNode(movieFolder.getId(), "folder.jpg"));
        assertNotNull(queryChildNode(movieFolder.getId(), "backdrop.jpg"));
    }

    /**
     * 图片完整的条目自动削刮不重复下载已有图片：连续两次非强制削刮，TMDB 图片仅下载一次。
     */
    @Test
    void shouldNotRedownloadExistingArtwork() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata metadata = fullMetadata(user.getId(), "movie", 1000L);
        metadata.setRawJson("{\"poster_path\":\"/p.jpg\"}");
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(metadata);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1, 2, 3});

        scrapeAwaitIdle(directory, user.getId(), false);
        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService, times(1)).downloadArtwork(anyString(), anyString());
        assertTrue(mediaMovieMapper.selectById(movie.getId()).getMetadataComplete());
        assertNotNull(queryChildNode(movieFolder.getId(), "folder.jpg"));
    }

    // ---------- 工单 05：本地优先新语义与 NFO 缺失补回 ----------

    /**
     * 本地 NFO 字段残缺（仅 title+tmdbid，缺 plot/rating）：削刮后缺失字段由 TMDB 补全
     * （fetchDetailV2，不重新自动匹配），NFO 已有字段不被远端覆盖，movie.nfo 被整体重写含补全字段。
     */
    @Test
    void shouldEnrichLocalNfoMissingFieldsFromTmdbAndRewrite() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo parentFolder = createFolder(user.getId(), movieFolder.getId(), "Iron Man 2008");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildTextFile("movie.nfo", """
                <movie>
                  <tmdbid>1726</tmdbid>
                  <title>钢铁侠</title>
                </movie>
                """), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildFile("poster.jpg"), user.getId(), parentFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), parentFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata remote = fullMetadata(user.getId(), "movie", 1726L);
        remote.setTitle("钢铁侠（远端标题）");
        when(tmdbService.fetchDetailV2(eq(user.getId()), eq(1726L), eq("movie"))).thenReturn(remote);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), anyString(), any());
        verify(tmdbService).fetchDetailV2(user.getId(), 1726L, "movie");
        MediaMetadata metadata = mediaMetadataMapper.selectOne(owner("movie", movie.getId()));
        assertEquals("local_nfo", metadata.getSource());
        // 本地已有 title 不被远端覆盖，缺失 plot/rating/releaseDate 由 TMDB 补全
        assertEquals("钢铁侠", metadata.getTitle());
        assertEquals("托尼·斯塔克打造钢铁战衣", metadata.getOverview());
        assertEquals(Double.valueOf(7.6), metadata.getVoteAverage());
        assertEquals("2008-04-30", metadata.getReleaseDate());
        assertTrue(mediaMovieMapper.selectById(movie.getId()).getMetadataComplete());
        // movie.nfo 被整体重写：含补全的 plot/rating
        String nfoContent = downloadText(queryChildNode(parentFolder.getId(), "movie.nfo").getId(), user.getId());
        assertTrue(nfoContent.contains("<plot>托尼·斯塔克打造钢铁战衣</plot>"), "movie.nfo 应整体重写含补全简介");
        assertTrue(nfoContent.contains("<rating>7.6</rating>"));
    }

    /**
     * 仅有本地图片（poster.jpg）无 NFO：文本字段由 TMDB 自动匹配补全并写回 movie.nfo，
     * 海报文件节点仍指向本地图（沿用不覆盖）。
     */
    @Test
    void shouldEnrichLocalImageOnlyMovie() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo parentFolder = createFolder(user.getId(), movieFolder.getId(), "Iron Man 2008");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildFile("poster.jpg"), user.getId(), parentFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), parentFolder.getId(), "Iron Man", 2008, videoFile.getId());

        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any()))
                .thenReturn(fullMetadata(user.getId(), "movie", 1000L));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaMetadata metadata = mediaMetadataMapper.selectOne(owner("movie", movie.getId()));
        assertEquals("local_nfo", metadata.getSource());
        assertEquals("钢铁侠", metadata.getTitle());
        assertEquals(1000L, metadata.getTmdbId());
        // 图片沿用本地
        assertEquals(queryChildNode(parentFolder.getId(), "poster.jpg").getId(), metadata.getPosterFileNodeId());
        assertTrue(mediaMovieMapper.selectById(movie.getId()).getMetadataComplete());
        // 文本字段补全并写回 movie.nfo
        FileNode nfoNode = queryChildNode(parentFolder.getId(), "movie.nfo");
        assertNotNull(nfoNode);
        String nfoContent = downloadText(nfoNode.getId(), user.getId());
        assertTrue(nfoContent.contains("<plot>托尼·斯塔克打造钢铁战衣</plot>"));
    }

    /**
     * NFO 缺失纳入削刮候选：TMDB 削刮写回 movie.nfo 后物理删除 NFO → 再削刮自动重建，
     * metadataId 不变（不重新匹配，走工单 03 复用/匹配补全路径）。
     */
    @Test
    void shouldRebuildDeletedMovieNfo() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata metadata = fullMetadata(user.getId(), "movie", 1000L);
        metadata.setRawJson("{\"poster_path\":\"/p.jpg\",\"backdrop_path\":\"/b.jpg\"}");
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(metadata);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);
        String metadataId = mediaMovieMapper.selectById(movie.getId()).getMetadataId();
        assertNotNull(metadataId);
        assertNotNull(queryChildNode(movieFolder.getId(), "movie.nfo"));

        // 物理删除 movie.nfo（NFO 不进完整性 5 项校验，缺失检测在削刮候选层）→ 再削刮重建
        fileMapper.deleteById(queryChildNode(movieFolder.getId(), "movie.nfo").getId());
        clearInvocations(tmdbService);
        scrapeAwaitIdle(directory, user.getId(), false);

        assertNotNull(queryChildNode(movieFolder.getId(), "movie.nfo"));
        assertEquals(metadataId, mediaMovieMapper.selectById(movie.getId()).getMetadataId());
        assertTrue(mediaMovieMapper.selectById(movie.getId()).getMetadataComplete());
    }

    /**
     * 剧集 NFO 缺失重建：TMDB 削刮写回 tvshow.nfo 与集 nfo 后，删 tvshow.nfo 再削刮重建；
     * 删集 nfo（任一集缺同名 nfo 聚合到剧行）再削刮重建。
     */
    @Test
    void shouldRebuildDeletedSeriesNfoAndEpisodeNfo() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo epFile = fileService.upload(buildFile("亮剑.S01E01.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        MediaSeries series = seedSeries(directory, user.getId(), seriesFolder.getId(), "亮剑", null);
        MediaSeason season = seedSeason(series.getId(), seasonFolder.getId(), 1);
        seedEpisode(series.getId(), season.getId(), 1, epFile.getId());

        MediaMetadata seriesMeta = fullMetadata(user.getId(), "series", 2000L);
        seriesMeta.setRawJson("{\"poster_path\":\"/s.jpg\",\"backdrop_path\":\"/b.jpg\"}");
        MediaMetadata seasonMeta = fullMetadata(user.getId(), "season", null);
        seasonMeta.setRawJson("{\"poster_path\":\"/sp.jpg\"}");
        MediaMetadata epMeta = fullMetadata(user.getId(), "episode", null);
        epMeta.setRawJson("{\"still_path\":\"/st.jpg\"}");
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("tv"), eq("亮剑"), isNull())).thenReturn(seriesMeta);
        when(tmdbService.fetchDetailV2(eq(user.getId()), eq(2000L), eq("tv"))).thenReturn(seriesMeta);
        when(tmdbService.fetchSeasonV2(eq(user.getId()), eq(2000L), eq(1)))
                .thenReturn(new TmdbService.SeasonFetchV2(seasonMeta, Map.of(1, epMeta)));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);
        String metadataId = mediaSeriesMapper.selectById(series.getId()).getMetadataId();
        assertNotNull(metadataId);
        assertNotNull(queryChildNode(seriesFolder.getId(), "tvshow.nfo"));
        assertNotNull(queryChildNode(seasonFolder.getId(), "亮剑.S01E01.1080p.nfo"));

        // 删 tvshow.nfo（本地图仍在，走本地优先 + 匹配补全路径）→ 再削刮重建
        fileMapper.deleteById(queryChildNode(seriesFolder.getId(), "tvshow.nfo").getId());
        clearInvocations(tmdbService);
        scrapeAwaitIdle(directory, user.getId(), false);
        assertNotNull(queryChildNode(seriesFolder.getId(), "tvshow.nfo"));

        // 删集 nfo（任一集缺同名 nfo 聚合到剧行）→ 再削刮重建
        fileMapper.deleteById(queryChildNode(seasonFolder.getId(), "亮剑.S01E01.1080p.nfo").getId());
        clearInvocations(tmdbService);
        scrapeAwaitIdle(directory, user.getId(), false);
        assertNotNull(queryChildNode(seasonFolder.getId(), "亮剑.S01E01.1080p.nfo"));
        assertEquals(metadataId, mediaSeriesMapper.selectById(series.getId()).getMetadataId());
    }

    // ---------- 保留行为：电影 TMDB 匹配/写回、剧匹配失败、年份匹配 ----------

    /**
     * 电影削刮：文件名匹配失败时用电影文件夹名兜底。
     */
    @Test
    void shouldFallbackToMovieFolderNameWhenFileNameNotMatched() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo parentFolder = createFolder(user.getId(), movieFolder.getId(), "Iron Man 2008");
        FileNodeVo videoFile = fileService.upload(buildFile("randomfile.mkv"), user.getId(), parentFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), parentFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata metadata = fullMetadata(user.getId(), "movie", 1000L);
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any()))
                .thenAnswer(inv -> "Iron Man".equals(inv.getArgument(2)) ? metadata : null);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaMovie after = mediaMovieMapper.selectById(movie.getId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), after.getMatchStatus());
        assertNotNull(after.getMetadataId());
        assertEquals(MediaScrapeStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScrapeStatus());
    }

    /**
     * 电影削刮 TMDB 路径写回：匹配成功后下载图片并写入视频目录（folder.jpg/backdrop.jpg + movie.nfo）。
     */
    @Test
    void shouldPersistArtworkAfterTmdbScrape() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata metadata = fullMetadata(user.getId(), "movie", 1000L);
        metadata.setRawJson("{\"poster_path\":\"/p.jpg\",\"backdrop_path\":\"/b.jpg\"}");
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(metadata);
        when(tmdbService.downloadArtwork("/p.jpg", "poster")).thenReturn(new byte[]{1, 2, 3});
        when(tmdbService.downloadArtwork("/b.jpg", "backdrop")).thenReturn(new byte[]{4, 5});

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaMovie after = mediaMovieMapper.selectById(movie.getId());
        MediaMetadata meta = mediaMetadataMapper.selectOne(owner("movie", movie.getId()));
        assertEquals("persisted", meta.getPersistStatus());
        FileNode poster = queryChildNode(movieFolder.getId(), "folder.jpg");
        FileNode fanart = queryChildNode(movieFolder.getId(), "backdrop.jpg");
        FileNode nfo = queryChildNode(movieFolder.getId(), "movie.nfo");
        assertEquals(poster.getId(), meta.getPosterFileNodeId());
        assertEquals(fanart.getId(), meta.getBackdropFileNodeId());
        assertEquals(3L, poster.getSize());
        assertEquals(2L, fanart.getSize());
        assertTrue(nfo.getSize() > 0);
        assertNotNull(after.getMetadataId());
    }

    /**
     * 写回失败（图片下载失败）：元数据正常保留并绑定，persist_status=failed，削刮结果不受影响。
     */
    @Test
    void shouldKeepMetadataWhenPersistFails() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata metadata = fullMetadata(user.getId(), "movie", 1001L);
        metadata.setRawJson("{\"poster_path\":\"/p.jpg\"}");
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(metadata);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(null);

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaMovie after = mediaMovieMapper.selectById(movie.getId());
        assertNotNull(after.getMetadataId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), after.getMatchStatus());
        MediaMetadata meta = mediaMetadataMapper.selectOne(owner("movie", movie.getId()));
        assertEquals("failed", meta.getPersistStatus());
        assertEquals("钢铁侠", meta.getTitle());
        assertEquals(MediaScrapeStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScrapeStatus());
    }

    /**
     * 剧削刮匹配失败：置未匹配、清空元数据关联，不产生季/集请求。
     */
    @Test
    void shouldMarkSeriesUnmatchedWhenScrapeFailed() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "不存在的剧xyz");
        FileNodeVo episodeFile = fileService.upload(buildFile("xyz.S01E01.mkv"), user.getId(), seriesFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        MediaSeries series = seedSeries(directory, user.getId(), seriesFolder.getId(), "不存在的剧xyz", null);
        seedEpisode(series.getId(), seedSeason(series.getId(), seriesFolder.getId(), 1).getId(), 1, episodeFile.getId());

        when(tmdbService.autoMatchV2(eq(user.getId()), eq("tv"), anyString(), isNull())).thenReturn(null);

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaSeries after = mediaSeriesMapper.selectById(series.getId());
        assertEquals(MediaMatchStatus.UNMATCHED.getCode(), after.getMatchStatus());
        assertNull(after.getMetadataId());
        verify(tmdbService, never()).fetchSeasonV2(any(), any(), any());
        assertEquals(0, mediaMetadataMapper.selectCount(new LambdaQueryWrapper<MediaMetadata>()
                .eq(MediaMetadata::getUserId, user.getId())));
    }

    /**
     * 剧削刮：使用剧文件夹解析出的首播年份进行匹配。
     */
    @Test
    void shouldScrapeSeriesWithReleaseYear() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑 (2005)");
        FileNodeVo episodeFile = fileService.upload(buildFile("亮剑.S01E01.mkv"), user.getId(), seriesFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        MediaSeries series = seedSeries(directory, user.getId(), seriesFolder.getId(), "亮剑", 2005);
        seedEpisode(series.getId(), seedSeason(series.getId(), seriesFolder.getId(), 1).getId(), 1, episodeFile.getId());

        MediaMetadata seriesMetadata = fullMetadata(user.getId(), "series", 2000L);
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("tv"), eq("亮剑"), eq(2005))).thenReturn(seriesMetadata);
        when(tmdbService.fetchSeasonV2(eq(user.getId()), eq(2000L), eq(1)))
                .thenReturn(new TmdbService.SeasonFetchV2(
                        fullMetadata(user.getId(), "season", null), Map.of()));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService).autoMatchV2(user.getId(), "tv", "亮剑", 2005);
        MediaSeries after = mediaSeriesMapper.selectById(series.getId());
        assertNotNull(after.getMetadataId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), after.getMatchStatus());
    }

    // ---------- 工具方法 ----------

    private FileNode queryChildNode(String parentId, String name) {
        return fileMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getParentId, parentId)
                .eq(FileNode::getName, name));
    }

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

    private MultipartFile buildFile(String name) {
        return new MockMultipartFile("file", name, "video/x-matroska", "video".getBytes());
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

    /**
     * 构造 5 项齐备的 TMDB 游离元数据（标题/简介/海报/发行日期/评分）。
     */
    private MediaMetadata fullMetadata(String userId, String ownerType, Long tmdbId) {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setUserId(userId);
        metadata.setOwnerType(ownerType);
        metadata.setSource("tmdb");
        metadata.setTmdbId(tmdbId);
        metadata.setTitle("钢铁侠");
        metadata.setOriginalTitle("Iron Man");
        metadata.setOverview("托尼·斯塔克打造钢铁战衣");
        metadata.setReleaseDate("2008-04-30");
        metadata.setVoteAverage(7.6);
        metadata.setRawJson("{\"poster_path\":\"/p.jpg\",\"backdrop_path\":\"/b.jpg\",\"still_path\":\"/s.jpg\"}");
        return metadata;
    }

    private void seedMetadata(String userId, String ownerType, String ownerId) {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setUserId(userId);
        metadata.setOwnerType(ownerType);
        metadata.setOwnerId(ownerId);
        metadata.setSource("tmdb");
        metadata.setTitle("钢铁侠");
        mediaMetadataMapper.insert(metadata);
    }

    private LambdaQueryWrapper<MediaMetadata> owner(String ownerType, String ownerId) {
        return new LambdaQueryWrapper<MediaMetadata>()
                .eq(MediaMetadata::getOwnerType, ownerType)
                .eq(MediaMetadata::getOwnerId, ownerId);
    }

    private MediaMovie seedMovie(MediaDirectory directory, String userId, String folderNodeId,
                                 String title, Integer releaseYear, String videoFileNodeId) {
        MediaMovie movie = new MediaMovie();
        movie.setUserId(userId);
        movie.setDirectoryId(directory.getId());
        movie.setSourceId(sourceIdOf(directory));
        movie.setFolderNodeId(folderNodeId);
        movie.setTitle(title);
        movie.setReleaseYear(releaseYear);
        movie.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
        movie.setMetadataComplete(false);
        movie.setProgressMs(0L);
        mediaMovieMapper.insert(movie);
        if (videoFileNodeId != null) {
            MediaMovieFile file = new MediaMovieFile();
            file.setMovieId(movie.getId());
            file.setFileNodeId(videoFileNodeId);
            mediaMovieFileMapper.insert(file);
        }
        return movie;
    }

    private MediaSeries seedSeries(MediaDirectory directory, String userId, String seriesFolderNodeId,
                                     String seriesName, Integer releaseYear) {
        MediaSeries series = new MediaSeries();
        series.setUserId(userId);
        series.setDirectoryId(directory.getId());
        series.setSourceId(sourceIdOf(directory));
        series.setFolderNodeId(seriesFolderNodeId);
        series.setSeriesName(seriesName);
        series.setReleaseYear(releaseYear);
        series.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
        series.setMetadataComplete(false);
        mediaSeriesMapper.insert(series);
        return series;
    }

    private MediaSeason seedSeason(String seriesId, String seasonFolderNodeId, Integer seasonNo) {
        MediaSeason season = new MediaSeason();
        season.setSeriesId(seriesId);
        season.setFolderNodeId(seasonFolderNodeId);
        season.setSeasonNo(seasonNo);
        mediaSeasonMapper.insert(season);
        return season;
    }

    private MediaEpisode seedEpisode(String seriesId, String seasonId, Integer episodeNo, String videoFileNodeId) {
        MediaEpisode episode = new MediaEpisode();
        episode.setSeriesId(seriesId);
        episode.setSeasonId(seasonId);
        episode.setEpisodeNo(episodeNo);
        mediaEpisodeMapper.insert(episode);
        if (videoFileNodeId != null) {
            MediaEpisodeFile file = new MediaEpisodeFile();
            file.setEpisodeId(episode.getId());
            file.setFileNodeId(videoFileNodeId);
            mediaEpisodeFileMapper.insert(file);
        }
        return episode;
    }

    private String sourceIdOf(MediaDirectory directory) {
        return mediaDirectorySourceMapper.selectOne(new LambdaQueryWrapper<MediaDirectorySource>()
                .eq(MediaDirectorySource::getDirectoryId, directory.getId())).getId();
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

    // ---------- 工单 05 review 回归：多版本（一行多文件）不抛重复键异常 ----------

    /**
     * 回归：多版本电影（一部电影的文件明细含多个视频）参与削刮时，NFO 缺失批量收集按电影取任一文件，
     * 不抛 toMap 重复键异常，削刮正常完成。
     */
    @Test
    void shouldScrapeMultiVersionMovieWithoutDuplicateKeyError() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo parentFolder = createFolder(user.getId(), movieFolder.getId(), "Iron Man 2008");
        FileNodeVo v1 = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), parentFolder.getId(), null);
        FileNodeVo v2 = fileService.upload(buildFile("Iron.Man.2008.2160p.mkv"), user.getId(), parentFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), parentFolder.getId(), "Iron Man", 2008, v1.getId());
        MediaMovieFile second = new MediaMovieFile();
        second.setMovieId(movie.getId());
        second.setFileNodeId(v2.getId());
        mediaMovieFileMapper.insert(second);
        MediaMetadata metadata = fullMetadata(user.getId(), "movie", 1000L);
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(metadata);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        assertEquals(MediaMatchStatus.MATCHED.getCode(), mediaMovieMapper.selectById(movie.getId()).getMatchStatus());
        assertEquals(MediaScrapeStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScrapeStatus());
    }

    /**
     * 回归：多版本集（一集的文件明细含多个视频）所属剧集参与削刮时，NFO 缺失批量收集按集取任一文件，
     * 不抛 toMap 重复键异常，削刮正常完成。
     */
    @Test
    void shouldScrapeMultiVersionEpisodeWithoutDuplicateKeyError() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo v1 = fileService.upload(buildFile("亮剑.S01E01.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        FileNodeVo v2 = fileService.upload(buildFile("亮剑.S01E01.2160p.mkv"), user.getId(), seasonFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        MediaSeries series = seedSeries(directory, user.getId(), seriesFolder.getId(), "亮剑", 2005);
        MediaSeason season = seedSeason(series.getId(), seasonFolder.getId(), 1);
        MediaEpisode episode = seedEpisode(series.getId(), season.getId(), 1, v1.getId());
        MediaEpisodeFile second = new MediaEpisodeFile();
        second.setEpisodeId(episode.getId());
        second.setFileNodeId(v2.getId());
        mediaEpisodeFileMapper.insert(second);
        MediaMetadata metadata = fullMetadata(user.getId(), "series", 1000L);
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("tv"), anyString(), any())).thenReturn(metadata);
        when(tmdbService.fetchSeasonV2(any(), any(), any())).thenReturn(null);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);

        assertEquals(MediaMatchStatus.MATCHED.getCode(), mediaSeriesMapper.selectById(series.getId()).getMatchStatus());
        assertEquals(MediaScrapeStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScrapeStatus());
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

    // ---------- 工单 06：单条刷新两模式（missing / force） ----------

    /**
     * 单条「刷新缺失」：TMDB 匹配条目物理删除图片产物后 refreshItem(mode=missing) 立即补回，
     * 已匹配字段不动、不重新自动匹配（复用元数据行重建产物）。
     */
    @Test
    void shouldRefreshMissingMovieRecreateDeletedPoster() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata metadata = fullMetadata(user.getId(), "movie", 1000L);
        metadata.setRawJson("{\"poster_path\":\"/p.jpg\",\"backdrop_path\":\"/b.jpg\"}");
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(metadata);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);
        String metadataId = mediaMovieMapper.selectById(movie.getId()).getMetadataId();
        assertNotNull(metadataId);
        String titleBefore = mediaMetadataMapper.selectById(metadataId).getTitle();
        assertNotNull(queryChildNode(movieFolder.getId(), "folder.jpg"));

        // 物理删除图片产物 → 单条「刷新缺失」立即补回
        for (String name : List.of("folder.jpg", "backdrop.jpg")) {
            FileNode node = queryChildNode(movieFolder.getId(), name);
            if (node != null) {
                fileMapper.deleteById(node.getId());
            }
        }
        clearInvocations(tmdbService);
        mediaScrapeService.refreshItem(metadataId, user.getId(), "missing");

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), anyString(), any());
        MediaMetadata after = mediaMetadataMapper.selectById(metadataId);
        assertEquals(titleBefore, after.getTitle());
        assertNotNull(queryChildNode(movieFolder.getId(), "folder.jpg"));
        assertNotNull(queryChildNode(movieFolder.getId(), "backdrop.jpg"));
        assertTrue(mediaMovieMapper.selectById(movie.getId()).getMetadataComplete());
    }

    /**
     * 单条「强制刷新」：mock fetchDetailV2 返回变更字段与新图 → refreshItem(mode=force) 字段被覆盖、
     * 图片重新下载覆盖（downloadArtwork 再次被调）、NFO 重写。
     */
    @Test
    void shouldRefreshForceMovieOverrideFieldsAndArtwork() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata metadata = fullMetadata(user.getId(), "movie", 1000L);
        metadata.setRawJson("{\"poster_path\":\"/p.jpg\",\"backdrop_path\":\"/b.jpg\"}");
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(metadata);
        when(tmdbService.downloadArtwork("/p.jpg", "poster")).thenReturn(new byte[]{1, 2, 3});
        when(tmdbService.downloadArtwork("/b.jpg", "backdrop")).thenReturn(new byte[]{4, 5});

        scrapeAwaitIdle(directory, user.getId(), false);
        String metadataId = mediaMovieMapper.selectById(movie.getId()).getMetadataId();
        assertNotNull(metadataId);
        long posterSizeBefore = queryChildNode(movieFolder.getId(), "folder.jpg").getSize();
        assertNotNull(queryChildNode(movieFolder.getId(), "movie.nfo"));

        // force 刷新：远端返回变更后的字段与新图
        MediaMetadata changed = fullMetadata(user.getId(), "movie", 1000L);
        changed.setTitle("钢铁侠（强制刷新）");
        changed.setOverview("强制刷新后的简介");
        when(tmdbService.fetchDetailV2(eq(user.getId()), eq(1000L), eq("movie"))).thenReturn(changed);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{9, 9});

        mediaScrapeService.refreshItem(metadataId, user.getId(), "force");

        verify(tmdbService).fetchDetailV2(user.getId(), 1000L, "movie");
        // 图片重新下载覆盖（downloadArtwork 再次被调，poster 内容变化）
        verify(tmdbService, times(2)).downloadArtwork("/p.jpg", "poster");
        MediaMetadata after = mediaMetadataMapper.selectById(metadataId);
        assertEquals("钢铁侠（强制刷新）", after.getTitle());
        assertEquals("强制刷新后的简介", after.getOverview());
        FileNode posterAfter = queryChildNode(movieFolder.getId(), "folder.jpg");
        assertEquals(2L, posterAfter.getSize());
        assertNotEquals(posterSizeBefore, posterAfter.getSize());
        // NFO 整体重写含新字段
        String nfoContent = downloadText(queryChildNode(movieFolder.getId(), "movie.nfo").getId(), user.getId());
        assertTrue(nfoContent.contains("钢铁侠（强制刷新）"));
        assertTrue(nfoContent.contains("强制刷新后的简介"));
    }

    /**
     * manual 条目「强制刷新」不再拒绝（工单 07）：不抛异常、不重新匹配、不覆盖文本字段，
     * 复用既有元数据行按 force 语义全量替换图片/NFO 产物（先删 poster 再 force → poster 按 rawJson 重建）。
     */
    @Test
    void shouldForceRefreshManualMovieRebuildArtifactsOnly() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata manualMeta = fullMetadata(user.getId(), "movie", 1000L);
        when(tmdbService.fetchDetailV2(eq(user.getId()), eq(1000L), eq("movie"))).thenReturn(manualMeta);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});
        MediaMatchUpdateDto dto = new MediaMatchUpdateDto();
        dto.setTmdbId(1000L);
        dto.setMediaType("movie");
        mediaItemService.updateMatch(movie.getId(), dto, user.getId());

        MediaMovie manual = mediaMovieMapper.selectById(movie.getId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), manual.getMatchStatus());
        String metadataId = manual.getMetadataId();
        assertNotNull(metadataId);
        String titleBefore = mediaMetadataMapper.selectById(metadataId).getTitle();
        assertNotNull(queryChildNode(movieFolder.getId(), "folder.jpg"));

        // 物理删除海报产物 → manual 单条 force 刷新：按 rawJson 重建，不重新匹配、文本字段不变
        fileMapper.deleteById(queryChildNode(movieFolder.getId(), "folder.jpg").getId());
        clearInvocations(tmdbService);
        mediaScrapeService.refreshItem(metadataId, user.getId(), "force");

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), anyString(), any());
        verify(tmdbService, never()).fetchDetailV2(any(), any(), any());
        MediaMovie after = mediaMovieMapper.selectById(movie.getId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), after.getMatchStatus());
        assertEquals(metadataId, after.getMetadataId());
        assertEquals(titleBefore, mediaMetadataMapper.selectById(metadataId).getTitle());
        assertNotNull(queryChildNode(movieFolder.getId(), "folder.jpg"));
        assertNotNull(queryChildNode(movieFolder.getId(), "backdrop.jpg"));
    }

    /**
     * manual 条目「刷新缺失」：字段不被覆盖，缺失图片产物补回。
     */
    @Test
    void shouldRefreshMissingManualMovieArtifactsOnly() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata manualMeta = fullMetadata(user.getId(), "movie", 1000L);
        when(tmdbService.fetchDetailV2(eq(user.getId()), eq(1000L), eq("movie"))).thenReturn(manualMeta);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});
        MediaMatchUpdateDto dto = new MediaMatchUpdateDto();
        dto.setTmdbId(1000L);
        dto.setMediaType("movie");
        mediaItemService.updateMatch(movie.getId(), dto, user.getId());

        MediaMovie manual = mediaMovieMapper.selectById(movie.getId());
        assertNotNull(manual.getMetadataId());
        assertNotNull(queryChildNode(movieFolder.getId(), "folder.jpg"));

        // 物理删除图片产物 → manual「刷新缺失」只补产物不改字段
        for (String name : List.of("folder.jpg", "backdrop.jpg")) {
            FileNode node = queryChildNode(movieFolder.getId(), name);
            if (node != null) {
                fileMapper.deleteById(node.getId());
            }
        }
        clearInvocations(tmdbService);
        mediaScrapeService.refreshItem(manual.getMetadataId(), user.getId(), "missing");

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), anyString(), any());
        verify(tmdbService, never()).fetchDetailV2(any(), any(), any());
        MediaMovie after = mediaMovieMapper.selectById(movie.getId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), after.getMatchStatus());
        assertEquals(manual.getMetadataId(), after.getMetadataId());
        assertEquals("钢铁侠", mediaMetadataMapper.selectById(manual.getMetadataId()).getTitle());
        assertNotNull(queryChildNode(movieFolder.getId(), "folder.jpg"));
        assertNotNull(queryChildNode(movieFolder.getId(), "backdrop.jpg"));
    }

    /**
     * 整库强制削刮：matched 条目 force=true 时重新拉 TMDB 详情（非复用旧行），
     * 已有图片被重新下载覆盖（产物全量替换）。
     */
    @Test
    void shouldForceScrapeMatchedMovieReloadDetail() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata metadata = fullMetadata(user.getId(), "movie", 1000L);
        metadata.setRawJson("{\"poster_path\":\"/p.jpg\",\"backdrop_path\":\"/b.jpg\"}");
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(metadata);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);
        String metadataId = mediaMovieMapper.selectById(movie.getId()).getMetadataId();
        assertNotNull(metadataId);
        long posterSizeBefore = queryChildNode(movieFolder.getId(), "folder.jpg").getSize();

        // force 整库削刮：matched 行重新拉详情 + 图片重新下载覆盖
        MediaMetadata changed = fullMetadata(user.getId(), "movie", 1000L);
        changed.setTitle("钢铁侠（force）");
        when(tmdbService.fetchDetailV2(eq(user.getId()), eq(1000L), eq("movie"))).thenReturn(changed);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{7, 7, 7});

        scrapeAwaitIdle(directory, user.getId(), true);

        verify(tmdbService).fetchDetailV2(user.getId(), 1000L, "movie");
        MediaMetadata after = mediaMetadataMapper.selectById(metadataId);
        assertEquals("钢铁侠（force）", after.getTitle());
        FileNode posterAfter = queryChildNode(movieFolder.getId(), "folder.jpg");
        assertEquals(3L, posterAfter.getSize());
        assertNotEquals(posterSizeBefore, posterAfter.getSize());
        assertTrue(mediaMovieMapper.selectById(movie.getId()).getMetadataComplete());
    }

    /**
     * 单条剧集「强制刷新」：重新拉剧级详情全量覆盖字段（含季/集派生），剧级/季级图片重新下载覆盖。
     */
    @Test
    void shouldRefreshForceSeriesReloadDetailAndArtifacts() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo epFile = fileService.upload(buildFile("亮剑.S01E01.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        MediaSeries series = seedSeries(directory, user.getId(), seriesFolder.getId(), "亮剑", null);
        MediaSeason season = seedSeason(series.getId(), seasonFolder.getId(), 1);
        MediaEpisode episode = seedEpisode(series.getId(), season.getId(), 1, epFile.getId());

        MediaMetadata seriesMeta = fullMetadata(user.getId(), "series", 2000L);
        MediaMetadata seasonMeta = fullMetadata(user.getId(), "season", null);
        MediaMetadata epMeta = fullMetadata(user.getId(), "episode", null);
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("tv"), eq("亮剑"), isNull())).thenReturn(seriesMeta);
        when(tmdbService.fetchSeasonV2(eq(user.getId()), eq(2000L), eq(1)))
                .thenReturn(new TmdbService.SeasonFetchV2(seasonMeta, Map.of(1, epMeta)));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);
        String metadataId = mediaSeriesMapper.selectById(series.getId()).getMetadataId();
        assertNotNull(metadataId);
        long posterSizeBefore = queryChildNode(seriesFolder.getId(), "folder.jpg").getSize();

        // force 单条刷新：剧级字段覆盖 + 图片重新下载覆盖
        MediaMetadata changed = fullMetadata(user.getId(), "series", 2000L);
        changed.setTitle("亮剑（强制刷新）");
        when(tmdbService.fetchDetailV2(eq(user.getId()), eq(2000L), eq("tv"))).thenReturn(changed);
        when(tmdbService.fetchSeasonV2(eq(user.getId()), eq(2000L), eq(1)))
                .thenReturn(new TmdbService.SeasonFetchV2(seasonMeta, Map.of(1, epMeta)));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{8, 8});

        mediaScrapeService.refreshItem(metadataId, user.getId(), "force");

        verify(tmdbService).fetchDetailV2(user.getId(), 2000L, "tv");
        MediaMetadata after = mediaMetadataMapper.selectById(metadataId);
        assertEquals("亮剑（强制刷新）", after.getTitle());
        FileNode posterAfter = queryChildNode(seriesFolder.getId(), "folder.jpg");
        assertEquals(2L, posterAfter.getSize());
        assertNotEquals(posterSizeBefore, posterAfter.getSize());
        assertTrue(mediaSeriesMapper.selectById(series.getId()).getMetadataComplete());
    }

    /**
     * 单条刷新（missing）对季/集归属抛业务异常「请刷新所属剧集」。
     */
    @Test
    void shouldRejectRefreshOfEpisodeMetadata() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo epFile = fileService.upload(buildFile("亮剑.S01E01.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        MediaSeries series = seedSeries(directory, user.getId(), seriesFolder.getId(), "亮剑", null);
        MediaSeason season = seedSeason(series.getId(), seasonFolder.getId(), 1);
        MediaEpisode episode = seedEpisode(series.getId(), season.getId(), 1, epFile.getId());

        MediaMetadata epMeta = new MediaMetadata();
        epMeta.setUserId(user.getId());
        epMeta.setOwnerType("episode");
        epMeta.setOwnerId(episode.getId());
        epMeta.setSource("tmdb");
        epMeta.setTitle("集");
        mediaMetadataMapper.insert(epMeta);

        BusinessException e = assertThrows(BusinessException.class,
                () -> mediaScrapeService.refreshItem(epMeta.getId(), user.getId(), "missing"));
        assertEquals("请刷新所属剧集", e.getMessage());
    }

    // ---------- 工单 07：补回触发完备化与刷新语义对齐 ----------

    /**
     * 完整且已匹配电影删除背景图文件节点（backdrop 不纳入 5 项完整性，行仍完整）→ 整库非强制削刮
     * 自动补回：backdrop 重建、指针重绑，文本字段与 tmdbId 不变、不重新匹配（工单 07）。
     */
    @Test
    void shouldRefillBackdropOfCompleteMatchedMovie() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata metadata = fullMetadata(user.getId(), "movie", 1000L);
        metadata.setRawJson("{\"poster_path\":\"/p.jpg\",\"backdrop_path\":\"/b.jpg\"}");
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(metadata);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);
        String metadataId = mediaMovieMapper.selectById(movie.getId()).getMetadataId();
        assertNotNull(metadataId);
        assertTrue(mediaMovieMapper.selectById(movie.getId()).getMetadataComplete());
        String backdropIdBefore = mediaMetadataMapper.selectById(metadataId).getBackdropFileNodeId();
        assertNotNull(backdropIdBefore);
        assertNotNull(queryChildNode(movieFolder.getId(), "backdrop.jpg"));

        // 物理删除背景图文件节点（行仍完整）→ 削刮自动补回
        fileMapper.deleteById(backdropIdBefore);
        clearInvocations(tmdbService);
        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), anyString(), any());
        verify(tmdbService, never()).fetchDetailV2(any(), any(), any());
        MediaMovie after = mediaMovieMapper.selectById(movie.getId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), after.getMatchStatus());
        assertEquals(metadataId, after.getMetadataId());
        assertTrue(after.getMetadataComplete());
        MediaMetadata meta = mediaMetadataMapper.selectById(metadataId);
        assertEquals("钢铁侠", meta.getTitle());
        assertEquals(1000L, meta.getTmdbId());
        assertNotEquals(backdropIdBefore, meta.getBackdropFileNodeId());
        assertNotNull(queryChildNode(movieFolder.getId(), "backdrop.jpg"));
    }

    /**
     * 完整且已匹配剧集删除剧级背景图 → 整库非强制削刮自动补回（只看剧级元数据行，季海报/集剧照
     * 由完整性聚合覆盖，工单 07），不重新匹配、字段与绑定不变。
     */
    @Test
    void shouldRefillBackdropOfCompleteMatchedSeries() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo epFile = fileService.upload(buildFile("亮剑.S01E01.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        MediaSeries series = seedSeries(directory, user.getId(), seriesFolder.getId(), "亮剑", null);
        MediaSeason season = seedSeason(series.getId(), seasonFolder.getId(), 1);
        MediaEpisode episode = seedEpisode(series.getId(), season.getId(), 1, epFile.getId());

        MediaMetadata seriesMeta = fullMetadata(user.getId(), "series", 2000L);
        MediaMetadata seasonMeta = fullMetadata(user.getId(), "season", null);
        MediaMetadata epMeta = fullMetadata(user.getId(), "episode", null);
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("tv"), eq("亮剑"), isNull())).thenReturn(seriesMeta);
        when(tmdbService.fetchSeasonV2(eq(user.getId()), eq(2000L), eq(1)))
                .thenReturn(new TmdbService.SeasonFetchV2(seasonMeta, Map.of(1, epMeta)));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);
        String metadataId = mediaSeriesMapper.selectById(series.getId()).getMetadataId();
        assertNotNull(metadataId);
        assertTrue(mediaSeriesMapper.selectById(series.getId()).getMetadataComplete());
        String backdropIdBefore = mediaMetadataMapper.selectById(metadataId).getBackdropFileNodeId();
        assertNotNull(backdropIdBefore);
        assertNotNull(queryChildNode(seriesFolder.getId(), "backdrop.jpg"));

        // 物理删除剧级背景图文件节点（行仍完整）→ 削刮自动补回
        fileMapper.deleteById(backdropIdBefore);
        clearInvocations(tmdbService);
        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), anyString(), any());
        verify(tmdbService, never()).fetchDetailV2(any(), any(), any());
        MediaSeries after = mediaSeriesMapper.selectById(series.getId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), after.getMatchStatus());
        assertEquals(metadataId, after.getMetadataId());
        assertTrue(after.getMetadataComplete());
        MediaMetadata meta = mediaMetadataMapper.selectById(metadataId);
        assertNotEquals(backdropIdBefore, meta.getBackdropFileNodeId());
        assertNotNull(queryChildNode(seriesFolder.getId(), "backdrop.jpg"));
    }

    /**
     * 完整 manual 电影删除 movie.nfo → 整库非强制削刮自动重建，不重新匹配、字段与绑定不变（工单 07）。
     */
    @Test
    void shouldRebuildDeletedMovieNfoForManualRow() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata manualMeta = fullMetadata(user.getId(), "movie", 1000L);
        when(tmdbService.fetchDetailV2(eq(user.getId()), eq(1000L), eq("movie"))).thenReturn(manualMeta);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});
        MediaMatchUpdateDto dto = new MediaMatchUpdateDto();
        dto.setTmdbId(1000L);
        dto.setMediaType("movie");
        mediaItemService.updateMatch(movie.getId(), dto, user.getId());

        MediaMovie manual = mediaMovieMapper.selectById(movie.getId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), manual.getMatchStatus());
        assertTrue(manual.getMetadataComplete());
        String metadataId = manual.getMetadataId();
        assertNotNull(metadataId);
        assertNotNull(queryChildNode(movieFolder.getId(), "movie.nfo"));

        // 物理删除 movie.nfo → 整库非强制削刮自动重建
        fileMapper.deleteById(queryChildNode(movieFolder.getId(), "movie.nfo").getId());
        clearInvocations(tmdbService);
        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), anyString(), any());
        verify(tmdbService, never()).fetchDetailV2(any(), any(), any());
        assertNotNull(queryChildNode(movieFolder.getId(), "movie.nfo"));
        MediaMovie after = mediaMovieMapper.selectById(movie.getId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), after.getMatchStatus());
        assertEquals(metadataId, after.getMetadataId());
        assertEquals("钢铁侠", mediaMetadataMapper.selectById(metadataId).getTitle());
        assertEquals(1000L, mediaMetadataMapper.selectById(metadataId).getTmdbId());
    }

    /**
     * 完整 manual 剧集删除 tvshow.nfo → 整库非强制削刮自动重建，不重新匹配、字段与绑定不变（工单 07）。
     */
    @Test
    void shouldRebuildDeletedTvshowNfoForManualSeries() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo epFile = fileService.upload(buildFile("亮剑.S01E01.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        MediaSeries series = seedSeries(directory, user.getId(), seriesFolder.getId(), "亮剑", null);
        MediaSeason season = seedSeason(series.getId(), seasonFolder.getId(), 1);
        seedEpisode(series.getId(), season.getId(), 1, epFile.getId());

        MediaMetadata manualMeta = fullMetadata(user.getId(), "series", 2000L);
        MediaMetadata seasonMeta = fullMetadata(user.getId(), "season", null);
        MediaMetadata epMeta = fullMetadata(user.getId(), "episode", null);
        when(tmdbService.fetchDetailV2(eq(user.getId()), eq(2000L), eq("tv"))).thenReturn(manualMeta);
        when(tmdbService.fetchSeasonV2(eq(user.getId()), eq(2000L), eq(1)))
                .thenReturn(new TmdbService.SeasonFetchV2(seasonMeta, Map.of(1, epMeta)));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});
        MediaMatchUpdateDto dto = new MediaMatchUpdateDto();
        dto.setTmdbId(2000L);
        dto.setMediaType("tv");
        mediaItemService.updateMatch(series.getId(), dto, user.getId());

        MediaSeries manual = mediaSeriesMapper.selectById(series.getId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), manual.getMatchStatus());
        assertTrue(manual.getMetadataComplete());
        String metadataId = manual.getMetadataId();
        assertNotNull(metadataId);
        assertNotNull(queryChildNode(seriesFolder.getId(), "tvshow.nfo"));

        // 物理删除 tvshow.nfo → 整库非强制削刮自动重建
        fileMapper.deleteById(queryChildNode(seriesFolder.getId(), "tvshow.nfo").getId());
        clearInvocations(tmdbService);
        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), anyString(), any());
        verify(tmdbService, never()).fetchDetailV2(any(), any(), any());
        assertNotNull(queryChildNode(seriesFolder.getId(), "tvshow.nfo"));
        MediaSeries after = mediaSeriesMapper.selectById(series.getId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), after.getMatchStatus());
        assertEquals(metadataId, after.getMetadataId());
        assertEquals("钢铁侠", mediaMetadataMapper.selectById(metadataId).getTitle());
    }

    /**
     * 未匹配但带本地 movie.nfo 的电影整库 force 削刮（工单 07）：TMDB 自动匹配成功 → 文本字段全量覆盖
     * 为 TMDB 值（本地优先被 force 覆盖）、movie.nfo 重写为 TMDB 内容。
     */
    @Test
    void shouldForceScrapeUnmatchedMovieWithLocalNfoOverwriteByTmdb() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo parentFolder = createFolder(user.getId(), movieFolder.getId(), "Iron Man 2008");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildTextFile("movie.nfo", """
                <movie>
                  <title>本地标题</title>
                  <plot>本地简介</plot>
                </movie>
                """), user.getId(), parentFolder.getId(), null);
        fileService.upload(buildFile("poster.jpg"), user.getId(), parentFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), parentFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata remote = fullMetadata(user.getId(), "movie", 1000L);
        remote.setTitle("TMDB 覆盖标题");
        remote.setOverview("TMDB 覆盖简介");
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(remote);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), true);

        MediaMovie after = mediaMovieMapper.selectById(movie.getId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), after.getMatchStatus());
        assertNotNull(after.getMetadataId());
        MediaMetadata metadata = mediaMetadataMapper.selectOne(owner("movie", movie.getId()));
        assertEquals("tmdb", metadata.getSource());
        // 文本字段被 TMDB 全量覆盖（force 对本地来源行同样 TMDB 优先）
        assertEquals("TMDB 覆盖标题", metadata.getTitle());
        assertEquals("TMDB 覆盖简介", metadata.getOverview());
        // movie.nfo 重写为 TMDB 内容（本地标题不保留）
        String nfoContent = downloadText(queryChildNode(parentFolder.getId(), "movie.nfo").getId(), user.getId());
        assertTrue(nfoContent.contains("TMDB 覆盖标题"));
        assertFalse(nfoContent.contains("本地标题"));
    }

    /**
     * force 削刮 TMDB 拉取/匹配失败（电影，工单 07）：不 unmatch、不清空既有匹配，
     * 回退本地优先主流程维持原匹配状态与字段（force 退化为非强制 persist）。
     */
    @Test
    void shouldKeepMovieMatchWhenForcePullFails() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata metadata = fullMetadata(user.getId(), "movie", 1000L);
        metadata.setRawJson("{\"poster_path\":\"/p.jpg\",\"backdrop_path\":\"/b.jpg\"}");
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(metadata);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);
        String metadataId = mediaMovieMapper.selectById(movie.getId()).getMetadataId();
        assertNotNull(metadataId);

        // force 时 TMDB 拉取与自动匹配均失败 → 维持匹配与字段，不清空既有匹配
        clearInvocations(tmdbService);
        when(tmdbService.fetchDetailV2(eq(user.getId()), eq(1000L), eq("movie"))).thenReturn(null);
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(null);
        scrapeAwaitIdle(directory, user.getId(), true);

        MediaMovie after = mediaMovieMapper.selectById(movie.getId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), after.getMatchStatus());
        assertEquals(metadataId, after.getMetadataId());
        assertEquals("钢铁侠", mediaMetadataMapper.selectById(metadataId).getTitle());
        assertEquals(1000L, mediaMetadataMapper.selectById(metadataId).getTmdbId());
    }

    /**
     * force 削刮 TMDB 拉取/匹配失败（剧集，工单 07）：不 unmatch、不清空既有匹配，回退本地优先主流程。
     */
    @Test
    void shouldKeepSeriesMatchWhenForcePullFails() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        FileNodeVo epFile = fileService.upload(buildFile("亮剑.S01E01.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        MediaSeries series = seedSeries(directory, user.getId(), seriesFolder.getId(), "亮剑", null);
        MediaSeason season = seedSeason(series.getId(), seasonFolder.getId(), 1);
        seedEpisode(series.getId(), season.getId(), 1, epFile.getId());

        MediaMetadata seriesMeta = fullMetadata(user.getId(), "series", 2000L);
        MediaMetadata seasonMeta = fullMetadata(user.getId(), "season", null);
        MediaMetadata epMeta = fullMetadata(user.getId(), "episode", null);
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("tv"), eq("亮剑"), isNull())).thenReturn(seriesMeta);
        when(tmdbService.fetchSeasonV2(eq(user.getId()), eq(2000L), eq(1)))
                .thenReturn(new TmdbService.SeasonFetchV2(seasonMeta, Map.of(1, epMeta)));
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);
        String metadataId = mediaSeriesMapper.selectById(series.getId()).getMetadataId();
        assertNotNull(metadataId);

        // force 时 TMDB 拉取与自动匹配均失败 → 维持匹配与字段，不清空既有匹配
        clearInvocations(tmdbService);
        when(tmdbService.fetchDetailV2(eq(user.getId()), eq(2000L), eq("tv"))).thenReturn(null);
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("tv"), anyString(), any())).thenReturn(null);
        scrapeAwaitIdle(directory, user.getId(), true);

        MediaSeries after = mediaSeriesMapper.selectById(series.getId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), after.getMatchStatus());
        assertEquals(metadataId, after.getMetadataId());
        assertEquals("钢铁侠", mediaMetadataMapper.selectById(metadataId).getTitle());
        assertEquals(2000L, mediaMetadataMapper.selectById(metadataId).getTmdbId());
    }

    /**
     * 回收站链路（工单 07）：经真实文件删除入口把背景图删进回收站（FileNode 行物理删除；本测试库
     * 回收站即物理删行，与 FileRecycleServiceTest 删除方式一致，t_file_node 存在即有效）→
     * 判定为不存在 → 整库非强制削刮自动补回。
     */
    @Test
    void shouldRefillBackdropDeletedToTrash() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo videoFile = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), movieFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movie = seedMovie(directory, user.getId(), movieFolder.getId(), "Iron Man", 2008, videoFile.getId());

        MediaMetadata metadata = fullMetadata(user.getId(), "movie", 1000L);
        metadata.setRawJson("{\"poster_path\":\"/p.jpg\",\"backdrop_path\":\"/b.jpg\"}");
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any())).thenReturn(metadata);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);
        String metadataId = mediaMovieMapper.selectById(movie.getId()).getMetadataId();
        assertNotNull(metadataId);
        FileNode backdropNode = queryChildNode(movieFolder.getId(), "backdrop.jpg");
        assertNotNull(backdropNode);

        // 经回收站入口删除背景图（FileNode 行物理删除）→ 削刮判定为缺失并补回
        FileDeleteDto dto = new FileDeleteDto();
        dto.setIds(List.of(backdropNode.getId()));
        fileRecycleService.deleteToTrash(dto, user.getId());
        assertNull(queryChildNode(movieFolder.getId(), "backdrop.jpg"));

        clearInvocations(tmdbService);
        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService, never()).autoMatchV2(any(), anyString(), anyString(), any());
        assertNotNull(queryChildNode(movieFolder.getId(), "backdrop.jpg"));
        assertNotEquals(backdropNode.getId(), mediaMetadataMapper.selectById(metadataId).getBackdropFileNodeId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), mediaMovieMapper.selectById(movie.getId()).getMatchStatus());
        assertEquals(metadataId, mediaMovieMapper.selectById(movie.getId()).getMetadataId());
    }

    /**
     * 整库 missing 模式粒度（工单 07）：同库两条完整 matched 电影只删其中一条的 movie.nfo →
     * 削刮后被删的重建、未删的 NFO 内容不变、两条的 metadataId 均不变。
     */
    @Test
    void shouldRebuildOnlyMissingNfoInLibrary() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo aFolder = createFolder(user.getId(), movieFolder.getId(), "Iron Man 2008");
        FileNodeVo aVideo = fileService.upload(buildFile("Iron.Man.2008.1080p.mkv"), user.getId(), aFolder.getId(), null);
        FileNodeVo bFolder = createFolder(user.getId(), movieFolder.getId(), "Inception 2010");
        FileNodeVo bVideo = fileService.upload(buildFile("Inception.2010.1080p.mkv"), user.getId(), bFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        MediaMovie movieA = seedMovie(directory, user.getId(), aFolder.getId(), "Iron Man", 2008, aVideo.getId());
        MediaMovie movieB = seedMovie(directory, user.getId(), bFolder.getId(), "Inception", 2010, bVideo.getId());

        MediaMetadata metaA = fullMetadata(user.getId(), "movie", 1000L);
        MediaMetadata metaB = fullMetadata(user.getId(), "movie", 2000L);
        when(tmdbService.autoMatchV2(eq(user.getId()), eq("movie"), anyString(), any()))
                .thenAnswer(inv -> "Iron Man".equals(inv.getArgument(2)) ? metaA
                        : "Inception".equals(inv.getArgument(2)) ? metaB : null);
        when(tmdbService.downloadArtwork(anyString(), anyString())).thenReturn(new byte[]{1});

        scrapeAwaitIdle(directory, user.getId(), false);
        String metadataIdA = mediaMovieMapper.selectById(movieA.getId()).getMetadataId();
        String metadataIdB = mediaMovieMapper.selectById(movieB.getId()).getMetadataId();
        assertNotNull(metadataIdA);
        assertNotNull(metadataIdB);
        assertTrue(mediaMovieMapper.selectById(movieA.getId()).getMetadataComplete());
        assertTrue(mediaMovieMapper.selectById(movieB.getId()).getMetadataComplete());
        String nfoBBefore = downloadText(queryChildNode(bFolder.getId(), "movie.nfo").getId(), user.getId());

        // 只删 A 的 movie.nfo → 削刮后 A 重建、B 的 NFO 内容不变
        fileMapper.deleteById(queryChildNode(aFolder.getId(), "movie.nfo").getId());
        clearInvocations(tmdbService);
        scrapeAwaitIdle(directory, user.getId(), false);

        assertNotNull(queryChildNode(aFolder.getId(), "movie.nfo"));
        assertEquals(nfoBBefore, downloadText(queryChildNode(bFolder.getId(), "movie.nfo").getId(), user.getId()));
        assertEquals(metadataIdA, mediaMovieMapper.selectById(movieA.getId()).getMetadataId());
        assertEquals(metadataIdB, mediaMovieMapper.selectById(movieB.getId()).getMetadataId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), mediaMovieMapper.selectById(movieA.getId()).getMatchStatus());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), mediaMovieMapper.selectById(movieB.getId()).getMatchStatus());
    }
}
