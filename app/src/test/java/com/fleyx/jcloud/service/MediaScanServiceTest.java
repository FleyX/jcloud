package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.dto.OperationItemDto;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @TempDir
    Path tempDir;

    /**
     * 文件移入剧文件夹后重扫：size/mtime 未变，但剧名应按新路径重新解析并清理旧剧。
     */
    @Test
    void shouldRegroupEpisodeAfterMoveToSeriesFolder() throws Exception {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "tv");
        FileNodeVo episode = fileService.upload(buildFile("HEVC.Test.S01E01.1080p.mkv", "video"),
                user.getId(), tvFolder.getId(), null);
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());

        // 首次扫描：文件直接位于视频目录下，剧名来自文件名解析
        mediaScanService.scan(directory.getId());
        MediaItem item = queryItem(directory.getId());
        assertEquals("Test", item.getSeriesName());

        // 移入“剧/季”目录结构后重扫，文件大小与修改时间均未变化
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "白鹿原 (2017)");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "s01");
        moveFileToFolder(user.getId(), episode.getId(), seasonFolder.getId());
        mediaScanService.scan(directory.getId());

        MediaItem moved = queryItem(directory.getId());
        assertEquals("白鹿原", moved.getSeriesName());
        List<MediaSeries> seriesList = mediaSeriesMapper.selectList(
                new LambdaQueryWrapper<MediaSeries>().eq(MediaSeries::getUserId, user.getId()));
        assertEquals(1, seriesList.size());
        assertEquals("白鹿原", seriesList.get(0).getSeriesName());
        assertEquals(seriesList.get(0).getId(), moved.getSeriesId());
    }

    /**
     * 文件未变动且路径未变时，重扫不改变剧分组。
     */
    @Test
    void shouldKeepSeriesWhenNothingChanged() throws Exception {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "tv");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "白鹿原 (2017)");
        fileService.upload(buildFile("HEVC.Test.S01E01.1080p.mkv", "video"), user.getId(), seriesFolder.getId(), null);
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());

        mediaScanService.scan(directory.getId());
        MediaItem item = queryItem(directory.getId());
        assertEquals("白鹿原", item.getSeriesName());

        mediaScanService.scan(directory.getId());
        MediaItem again = queryItem(directory.getId());
        assertEquals("白鹿原", again.getSeriesName());
        assertEquals(item.getSeriesId(), again.getSeriesId());
        assertEquals(1, mediaSeriesMapper.selectCount(
                new LambdaQueryWrapper<MediaSeries>().eq(MediaSeries::getUserId, user.getId())));
    }

    private MediaItem queryItem(String directoryId) {
        return mediaItemMapper.selectOne(
                new LambdaQueryWrapper<MediaItem>().eq(MediaItem::getDirectoryId, directoryId));
    }

    private MediaDirectory createTvDirectory(String userId, String folderNodeId) {
        MediaDirectory directory = new MediaDirectory();
        directory.setUserId(userId);
        directory.setFileNodeId(folderNodeId);
        directory.setName("电视");
        directory.setMediaType("tv");
        mediaDirectoryMapper.insert(directory);
        return directory;
    }

    private MultipartFile buildFile(String name, String content) {
        return new MockMultipartFile("file", name, "video/x-matroska", content.getBytes());
    }

    private FileNodeVo createFolder(String userId, String parentId, String name) {
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(parentId);
        dto.setName(name);
        return fileOperationService.createFolder(dto, userId);
    }

    private void moveFileToFolder(String userId, String fileId, String folderId) {
        FileExecuteOperationDto dto = new FileExecuteOperationDto();
        dto.setType("move");
        dto.setTargetParentId(folderId);
        OperationItemDto item = new OperationItemDto();
        item.setId(fileId);
        dto.setItems(List.of(item));
        fileOperationService.move(dto, userId);
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
