package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaScrapeStatus;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaDirectorySourceMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
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

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        when(tmdbService.autoMatch(eq("movie"), anyString(), any()))
                .thenAnswer(inv -> "Iron Man".equals(inv.getArgument(1)) ? metadata : null);

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

        verify(tmdbService, never()).autoMatch(anyString(), anyString(), any());
        MediaItem after = queryItem(directory.getId());
        assertEquals("manualmeta001", after.getMetadataId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), after.getMatchStatus());
    }

    /**
     * 电视削刮：剧级匹配应用到季与非手动集，拉取季/集元数据。
     */
    @Test
    void shouldScrapeSeriesAndFillSeasonEpisodeMetadata() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        fileService.upload(buildFile("亮剑.S01E01.1080p.mkv"), user.getId(), seasonFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        mediaScanService.scan(directory.getId());

        MediaMetadata seriesMetadata = buildMetadata("metatv0000001", 2000L);
        MediaMetadata seasonMetadata = buildMetadata("metaseason001", null);
        MediaMetadata episodeMetadata = buildMetadata("metaepisode01", null);
        when(tmdbService.autoMatch(eq("tv"), eq("亮剑"), isNull())).thenReturn(seriesMetadata);
        when(tmdbService.getOrFetchSeason(2000L, 1)).thenReturn(seasonMetadata);
        when(tmdbService.findEpisode(2000L, 1, 1)).thenReturn(episodeMetadata);

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
        fileService.upload(buildFile("xyz.S01E01.mkv"), user.getId(), seriesFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        mediaScanService.scan(directory.getId());

        when(tmdbService.autoMatch(eq("tv"), anyString(), isNull())).thenReturn(null);

        scrapeAwaitIdle(directory, user.getId(), false);

        MediaSeries series = mediaSeriesMapper.selectOne(
                new LambdaQueryWrapper<MediaSeries>().eq(MediaSeries::getUserId, user.getId()));
        assertEquals(MediaMatchStatus.UNMATCHED.getCode(), series.getMatchStatus());
        assertNull(series.getMetadataId());
        verify(tmdbService, never()).getOrFetchSeason(any(), any());
    }

    /**
     * 电视削刮：使用剧文件夹解析出的首播年份进行匹配。
     */
    @Test
    void shouldScrapeSeriesWithReleaseYear() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑 (2005)");
        fileService.upload(buildFile("亮剑.S01E01.mkv"), user.getId(), seriesFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId(), "tv");
        mediaScanService.scan(directory.getId());

        MediaSeries series = mediaSeriesMapper.selectOne(
                new LambdaQueryWrapper<MediaSeries>().eq(MediaSeries::getUserId, user.getId()));
        assertEquals(2005, series.getReleaseYear());

        MediaMetadata seriesMetadata = buildMetadata("metatv0000002", 3000L);
        when(tmdbService.autoMatch(eq("tv"), eq("亮剑"), eq(2005))).thenReturn(seriesMetadata);

        scrapeAwaitIdle(directory, user.getId(), false);

        verify(tmdbService).autoMatch("tv", "亮剑", 2005);
        MediaSeries after = mediaSeriesMapper.selectById(series.getId());
        assertEquals("metatv0000002", after.getMetadataId());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), after.getMatchStatus());
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
