package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileRenameDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaItem;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 媒体库扫描服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaScanServiceTest {

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
    private MediaDirectoryMapper mediaDirectoryMapper;

    @Autowired
    private MediaItemMapper mediaItemMapper;

    @Autowired
    private MediaSeriesMapper mediaSeriesMapper;

    @MockitoBean
    private MediaScrapeService mediaScrapeService;

    @TempDir
    Path tempDir;

    /**
     * 剧文件夹改名（补年份）后重扫：条目与剧的非手动匹配被重置（元数据显式清空）、
     * 首播年份回填，且扫描完成后自动提交非强制削刮。
     */
    @Test
    void shouldResetMatchAndBackfillYearWhenSeriesFolderRenamed() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "火星生活");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "s01");
        fileService.upload(buildFile("火星生活.s01e01.mp4"), user.getId(), seasonFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());

        // 模拟削刮完成状态：剧与集均已匹配
        MediaSeries series = querySeries(user.getId());
        series.setMetadataId("metaseries01");
        series.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
        mediaSeriesMapper.updateById(series);
        MediaItem item = queryItem(directory.getId());
        item.setMetadataId("metaepisode1");
        item.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
        mediaItemMapper.updateById(item);

        // 改名补年份后重扫
        FileRenameDto renameDto = new FileRenameDto();
        renameDto.setId(seriesFolder.getId());
        renameDto.setNewName("火星生活 (2018)");
        fileOperationService.rename(renameDto, user.getId());
        mediaScanService.scan(directory.getId());

        MediaItem afterItem = queryItem(directory.getId());
        assertEquals(MediaMatchStatus.UNMATCHED.getCode(), afterItem.getMatchStatus());
        assertNull(afterItem.getMetadataId());

        MediaSeries afterSeries = querySeries(user.getId());
        assertEquals(MediaMatchStatus.UNMATCHED.getCode(), afterSeries.getMatchStatus());
        assertNull(afterSeries.getMetadataId());
        assertEquals(2018, afterSeries.getReleaseYear());

        // 两次扫描均应在完成后自动提交非强制削刮
        verify(mediaScrapeService, times(2)).submitScrape(directory.getId(), user.getId(), false);
    }

    /**
     * 手动匹配的剧与集在改名重扫后保持不变，但仍会触发自动削刮。
     */
    @Test
    void shouldKeepManualMatchWhenSeriesFolderRenamed() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "火星生活");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "s01");
        fileService.upload(buildFile("火星生活.s01e01.mp4"), user.getId(), seasonFolder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());

        MediaSeries series = querySeries(user.getId());
        series.setMetadataId("metaseries01");
        series.setMatchStatus(MediaMatchStatus.MANUAL.getCode());
        mediaSeriesMapper.updateById(series);
        MediaItem item = queryItem(directory.getId());
        item.setMetadataId("metaepisode1");
        item.setMatchStatus(MediaMatchStatus.MANUAL.getCode());
        mediaItemMapper.updateById(item);

        FileRenameDto renameDto = new FileRenameDto();
        renameDto.setId(seriesFolder.getId());
        renameDto.setNewName("火星生活 (2018)");
        fileOperationService.rename(renameDto, user.getId());
        mediaScanService.scan(directory.getId());

        MediaItem afterItem = queryItem(directory.getId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), afterItem.getMatchStatus());
        assertEquals("metaepisode1", afterItem.getMetadataId());

        MediaSeries afterSeries = querySeries(user.getId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), afterSeries.getMatchStatus());
        assertEquals("metaseries01", afterSeries.getMetadataId());
        assertEquals(2018, afterSeries.getReleaseYear());
    }

    private MediaSeries querySeries(String userId) {
        return mediaSeriesMapper.selectOne(
                new LambdaQueryWrapper<MediaSeries>().eq(MediaSeries::getUserId, userId));
    }

    private MediaItem queryItem(String directoryId) {
        return mediaItemMapper.selectOne(
                new LambdaQueryWrapper<MediaItem>().eq(MediaItem::getDirectoryId, directoryId));
    }

    private MediaDirectory createDirectory(String userId, String folderNodeId) {
        MediaDirectory directory = new MediaDirectory();
        directory.setUserId(userId);
        directory.setFileNodeId(folderNodeId);
        directory.setName("测试目录");
        directory.setMediaType("tv");
        mediaDirectoryMapper.insert(directory);
        return directory;
    }

    private MultipartFile buildFile(String name) {
        return new MockMultipartFile("file", name, "video/mp4", "video".getBytes());
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
