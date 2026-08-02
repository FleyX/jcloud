package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaDirectorySourceMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaSubtitleMapper;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaSubtitle;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.MediaPlaybackInfoVo;
import com.fleyx.jcloud.model.vo.MediaSubtitleItemVo;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体播放服务测试（统一字幕列表、外部字幕读取、实际码率估算）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaPlaybackServiceTest {

    private static final String SRT_CONTENT = """
            1
            00:00:01,000 --> 00:00:04,000
            你好，世界

            2
            00:00:05,000 --> 00:00:06,000
            第二行
            """;

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
    private MediaPlaybackService mediaPlaybackService;

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

    @Autowired
    private SystemConfigService systemConfigService;

    @MockitoBean
    private MediaScrapeService mediaScrapeService;

    @TempDir
    Path tempDir;

    /**
     * 播放信息装配统一字幕列表：外部字幕默认优先、其余按标签排序；
     * ffprobe 码率缺失时按文件大小与时长估算实际码率。
     */
    @Test
    void shouldAssembleUnifiedSubtitleListAndEstimateBitRate() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo folder = createFolder(user.getId(), "电影");
        fileService.upload(buildFile("Movie.mkv", "video".getBytes()), user.getId(), folder.getId(), null);
        fileService.upload(buildFile("Movie.chs.srt", SRT_CONTENT.getBytes(StandardCharsets.UTF_8)),
                user.getId(), folder.getId(), null);
        fileService.upload(buildFile("Movie.eng.default.srt", SRT_CONTENT.getBytes(StandardCharsets.UTF_8)),
                user.getId(), folder.getId(), null);
        MediaDirectory directory = createMovieDirectory(user.getId(), folder.getId());
        mediaScanService.scan(directory.getId());

        MediaItem item = queryItem(directory.getId(), "Movie.mkv");
        // 假视频 ffprobe 探测失败回退扫描数据，手动补时长验证码率估算
        item.setDurationMs(10_000L);
        mediaItemMapper.updateById(item);

        MediaPlaybackInfoVo vo = mediaPlaybackService.getPlaybackInfo(item.getId(), user.getId());

        assertEquals(2, vo.getSubtitles().size());
        MediaSubtitleItemVo first = vo.getSubtitles().get(0);
        assertEquals("external", first.getType());
        assertEquals(Boolean.TRUE, first.getDefaulted());
        assertEquals("English", first.getLabel());
        assertTrue(first.getSubtitleId() != null && !first.getSubtitleId().isBlank());
        MediaSubtitleItemVo second = vo.getSubtitles().get(1);
        assertEquals("简体", second.getLabel());
        assertEquals(Boolean.FALSE, second.getDefaulted());

        // 估算码率 = 文件大小(5 字节) * 8 * 1000 / 10000ms = 4 bps
        assertEquals(4L, vo.getEffectiveBitRate());

        // 时长缺失时码率为 null
        item.setDurationMs(null);
        mediaItemMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MediaItem>()
                .eq(MediaItem::getId, item.getId()).set(MediaItem::getDurationMs, null));
        MediaPlaybackInfoVo vo2 = mediaPlaybackService.getPlaybackInfo(item.getId(), user.getId());
        assertNull(vo2.getEffectiveBitRate());
    }

    /**
     * 外部 srt 字幕经 ffmpeg 转为 webvtt 并缓存（ext_{fileNodeId}.vtt），二次读取复用缓存。
     */
    @Test
    void shouldConvertExternalSrtToVttAndReuseCache() throws Exception {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo folder = createFolder(user.getId(), "电影");
        fileService.upload(buildFile("Movie.mkv", "video".getBytes()), user.getId(), folder.getId(), null);
        fileService.upload(buildFile("Movie.zh.srt", SRT_CONTENT.getBytes(StandardCharsets.UTF_8)),
                user.getId(), folder.getId(), null);
        MediaDirectory directory = createMovieDirectory(user.getId(), folder.getId());
        mediaScanService.scan(directory.getId());

        MediaItem item = queryItem(directory.getId(), "Movie.mkv");
        MediaSubtitle subtitle = querySubtitle(item.getId());

        Path vtt = mediaPlaybackService.extractExternalSubtitle(item.getId(), subtitle.getId(), user.getId());
        assertTrue(Files.exists(vtt));
        assertEquals("ext_" + subtitle.getFileNodeId() + ".vtt", vtt.getFileName().toString());
        String content = Files.readString(vtt, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("WEBVTT"));
        assertTrue(content.contains("你好，世界"));

        // 二次读取命中缓存，返回同一路径
        Path cached = mediaPlaybackService.extractExternalSubtitle(item.getId(), subtitle.getId(), user.getId());
        assertEquals(vtt, cached);
    }

    /**
     * 本地 vtt 字幕原样返回字节，不做转换与缓存。
     */
    @Test
    void shouldReturnLocalVttDirectly() throws Exception {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo folder = createFolder(user.getId(), "电影");
        fileService.upload(buildFile("Movie.mkv", "video".getBytes()), user.getId(), folder.getId(), null);
        String vttContent = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n你好\n";
        fileService.upload(buildFile("Movie.vtt", vttContent.getBytes(StandardCharsets.UTF_8)),
                user.getId(), folder.getId(), null);
        MediaDirectory directory = createMovieDirectory(user.getId(), folder.getId());
        mediaScanService.scan(directory.getId());

        MediaItem item = queryItem(directory.getId(), "Movie.mkv");
        MediaSubtitle subtitle = querySubtitle(item.getId());

        Path path = mediaPlaybackService.extractExternalSubtitle(item.getId(), subtitle.getId(), user.getId());
        assertEquals(vttContent, Files.readString(path, StandardCharsets.UTF_8));
        // 原样返回用户空间内的原文件，而不是系统空间缓存
        assertEquals("Movie.vtt", path.getFileName().toString());
    }

    /**
     * 字幕记录不属于该条目时拒绝读取。
     */
    @Test
    void shouldRejectSubtitleNotBelongingToItem() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo folder = createFolder(user.getId(), "电影");
        fileService.upload(buildFile("MovieA.mkv", "video".getBytes()), user.getId(), folder.getId(), null);
        fileService.upload(buildFile("MovieA.zh.srt", SRT_CONTENT.getBytes(StandardCharsets.UTF_8)),
                user.getId(), folder.getId(), null);
        fileService.upload(buildFile("MovieB.mkv", "video".getBytes()), user.getId(), folder.getId(), null);
        MediaDirectory directory = createMovieDirectory(user.getId(), folder.getId());
        mediaScanService.scan(directory.getId());

        MediaItem itemA = queryItem(directory.getId(), "MovieA.mkv");
        MediaItem itemB = queryItem(directory.getId(), "MovieB.mkv");
        MediaSubtitle subtitle = querySubtitle(itemA.getId());

        assertThrows(BusinessException.class,
                () -> mediaPlaybackService.extractExternalSubtitle(itemB.getId(), subtitle.getId(), user.getId()));
        assertThrows(BusinessException.class,
                () -> mediaPlaybackService.extractExternalSubtitle(itemA.getId(), "nonexistent0", user.getId()));
    }

    private MediaItem queryItem(String directoryId, String fileName) {
        return mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                        .eq(MediaItem::getDirectoryId, directoryId))
                .stream()
                .filter(i -> fileName.equals(fileNodeName(i.getFileNodeId())))
                .findFirst().orElseThrow();
    }

    private String fileNodeName(String fileNodeId) {
        return fileMapper.selectById(fileNodeId).getName();
    }

    private MediaSubtitle querySubtitle(String itemId) {
        List<MediaSubtitle> subtitles = mediaSubtitleMapper.selectList(
                new LambdaQueryWrapper<MediaSubtitle>().eq(MediaSubtitle::getItemId, itemId));
        assertEquals(1, subtitles.size());
        return subtitles.get(0);
    }

    /**
     * 创建「其他」类型媒体库：视频文件在来源根目录下的散文件，仍走旧路径扫描（每个视频文件一行），
     * 供播放链路（字幕/码率/vtt）测试复用旧表条目（播放链路切换到新模型在 issue #19）。
     */
    private MediaDirectory createMovieDirectory(String userId, String folderNodeId) {
        MediaDirectory directory = new MediaDirectory();
        directory.setUserId(userId);
        directory.setName("测试媒体库");
        directory.setMediaType("other");
        mediaDirectoryMapper.insert(directory);
        MediaDirectorySource source = new MediaDirectorySource();
        source.setDirectoryId(directory.getId());
        source.setFileNodeId(folderNodeId);
        mediaDirectorySourceMapper.insert(source);
        return directory;
    }

    private FileNodeVo createFolder(String userId, String name) {
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(FileNodeConstants.ROOT_ID);
        dto.setName(name);
        return fileOperationService.createFolder(dto, userId);
    }

    private MultipartFile buildFile(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    private UserVo prepareUserWithStorageSpace() {
        Path spacePath = tempDir.resolve("space-" + System.nanoTime());
        StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
        spaceDto.setName("用户空间");
        spaceDto.setPath(spacePath.toString());
        StorageSpaceVo space = storageSpaceService.save(spaceDto);
        systemConfigService.setValue("system.storage.space.id", String.valueOf(space.getId()));

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
