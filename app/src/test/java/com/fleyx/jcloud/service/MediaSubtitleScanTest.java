package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaDirectorySourceMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaSubtitleMapper;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaSubtitle;
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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部字幕扫描关联测试：同目录前缀匹配关联、重扫重建（后加/删除字幕）、条目清理联动删除。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaSubtitleScanTest {

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
    private MediaDirectoryService mediaDirectoryService;

    @Autowired
    private MediaDirectoryMapper mediaDirectoryMapper;

    @Autowired
    private MediaDirectorySourceMapper mediaDirectorySourceMapper;

    @Autowired
    private MediaItemMapper mediaItemMapper;

    @Autowired
    private MediaSubtitleMapper mediaSubtitleMapper;

    @Autowired
    private FileMapper fileMapper;

    @MockitoBean
    private MediaScrapeService mediaScrapeService;

    @TempDir
    Path tempDir;

    /**
     * 同目录前缀匹配关联：Movie.chs.srt、Movie.eng.default.srt 关联到 Movie.mkv，
     * Movie2.srt 不误配，子目录中的同名字幕不关联；重扫后后加/删除的字幕关联正确重建。
     */
    @Test
    void shouldLinkAndRebuildExternalSubtitles() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        fileService.upload(buildFile("Movie.mkv"), user.getId(), folder.getId(), null);
        FileNodeVo chs = fileService.upload(buildFile("Movie.chs.srt"), user.getId(), folder.getId(), null);
        fileService.upload(buildFile("Movie.eng.default.srt"), user.getId(), folder.getId(), null);
        fileService.upload(buildFile("Movie2.srt"), user.getId(), folder.getId(), null);
        FileNodeVo subFolder = createFolder(user.getId(), folder.getId(), "子目录");
        fileService.upload(buildFile("Movie.jpn.srt"), user.getId(), subFolder.getId(), null);

        MediaDirectory directory = createDirectory(user.getId(), folder.getId());
        mediaScanService.scan(directory.getId());

        Map<String, MediaSubtitle> byLabel = subtitlesOf(directory.getId()).stream()
                .collect(Collectors.toMap(MediaSubtitle::getLabel, Function.identity()));
        assertEquals(2, byLabel.size());
        assertEquals("简体", byLabel.get("简体").getLabel());
        assertFalse(byLabel.get("简体").getIsDefault());
        assertEquals("srt", byLabel.get("简体").getFormat());
        assertEquals("English", byLabel.get("English").getLabel());
        assertTrue(byLabel.get("English").getIsDefault());

        // 后加同目录字幕后重扫，关联重建
        fileService.upload(buildFile("Movie.jpn.srt"), user.getId(), folder.getId(), null);
        mediaScanService.scan(directory.getId());
        assertEquals(3, subtitlesOf(directory.getId()).size());
        assertTrue(subtitlesOf(directory.getId()).stream().anyMatch(s -> "日语".equals(s.getLabel())));

        // 删除字幕文件后重扫，关联移除
        fileMapper.deleteById(chs.getId());
        mediaScanService.scan(directory.getId());
        List<MediaSubtitle> afterDelete = subtitlesOf(directory.getId());
        assertEquals(2, afterDelete.size());
        assertTrue(afterDelete.stream().noneMatch(s -> "简体".equals(s.getLabel())));
    }

    /**
     * 视频文件消失后条目被清理，其外部字幕记录一并删除。
     */
    @Test
    void shouldDeleteSubtitlesWhenItemFileRemoved() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo video = fileService.upload(buildFile("Movie.mkv"), user.getId(), folder.getId(), null);
        fileService.upload(buildFile("Movie.srt"), user.getId(), folder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), folder.getId());
        mediaScanService.scan(directory.getId());
        assertEquals(1, subtitlesOf(directory.getId()).size());

        fileMapper.deleteById(video.getId());
        mediaScanService.scan(directory.getId());
        assertEquals(0, subtitlesOf(directory.getId()).size());
    }

    /**
     * 媒体库删除时，其条目的外部字幕记录一并删除。
     */
    @Test
    void shouldDeleteSubtitlesWhenDirectoryDeleted() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        fileService.upload(buildFile("Movie.mkv"), user.getId(), folder.getId(), null);
        fileService.upload(buildFile("Movie.srt"), user.getId(), folder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), folder.getId());
        mediaScanService.scan(directory.getId());
        assertEquals(1, subtitlesOf(directory.getId()).size());

        mediaDirectoryService.delete(directory.getId(), user.getId());
        assertEquals(0, subtitlesOf(directory.getId()).size());
    }

    private List<MediaSubtitle> subtitlesOf(String directoryId) {
        List<String> itemIds = mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                        .eq(MediaItem::getDirectoryId, directoryId))
                .stream().map(MediaItem::getId).toList();
        if (itemIds.isEmpty()) {
            return List.of();
        }
        return mediaSubtitleMapper.selectList(
                new LambdaQueryWrapper<MediaSubtitle>().in(MediaSubtitle::getItemId, itemIds));
    }

    private MediaDirectory createDirectory(String userId, String folderNodeId) {
        MediaDirectory directory = new MediaDirectory();
        directory.setUserId(userId);
        directory.setName("测试媒体库");
        directory.setMediaType("movie");
        mediaDirectoryMapper.insert(directory);
        MediaDirectorySource source = new MediaDirectorySource();
        source.setDirectoryId(directory.getId());
        source.setFileNodeId(folderNodeId);
        mediaDirectorySourceMapper.insert(source);
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
