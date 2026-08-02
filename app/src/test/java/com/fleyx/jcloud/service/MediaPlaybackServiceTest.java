package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaDirectorySourceMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSubtitleMapper;
import com.fleyx.jcloud.mapper.MediaSeriesV2Mapper;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaOther;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体播放服务测试（issue #19 起播放链路切新模型）：
 * 播放信息基于文件明细行事实、进度记录到标题级新行（电影/集/其他）、多版本共享进度且
 * 续播按 last_play_file_id 定位版本、外部字幕按明细行关联、统一字幕列表与实际码率估算、vtt 转换与缓存。
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
    private MediaItemService mediaItemService;

    @Autowired
    private MediaPlaybackService mediaPlaybackService;

    @Autowired
    private MediaDirectoryMapper mediaDirectoryMapper;

    @Autowired
    private MediaDirectorySourceMapper mediaDirectorySourceMapper;

    @Autowired
    private MediaMovieMapper mediaMovieMapper;

    @Autowired
    private MediaMovieFileMapper mediaMovieFileMapper;

    @Autowired
    private MediaEpisodeMapper mediaEpisodeMapper;

    @Autowired
    private MediaEpisodeFileMapper mediaEpisodeFileMapper;

    @Autowired
    private MediaOtherMapper mediaOtherMapper;

    @Autowired
    private MediaSeriesV2Mapper mediaSeriesV2Mapper;

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
     * ffprobe 码率缺失时按明细行文件大小与时长估算实际码率；进度取电影标题级行。
     */
    @Test
    void shouldAssembleUnifiedSubtitleListAndEstimateBitRate() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        fileService.upload(buildFile("沙丘.mkv", "video".getBytes()), user.getId(), dune.getId(), null);
        fileService.upload(buildFile("沙丘.chs.srt", SRT_CONTENT.getBytes(StandardCharsets.UTF_8)),
                user.getId(), dune.getId(), null);
        fileService.upload(buildFile("沙丘.eng.default.srt", SRT_CONTENT.getBytes(StandardCharsets.UTF_8)),
                user.getId(), dune.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());

        MediaMovie movie = querySingleMovie(directory.getId());
        MediaMovieFile file = queryMovieFile(movie.getId());
        // 假视频 ffprobe 探测失败回退明细行数据，手动补时长验证码率估算
        file.setDurationMs(10_000L);
        mediaMovieFileMapper.updateById(file);

        MediaPlaybackInfoVo vo = mediaPlaybackService.getPlaybackInfo(movie.getId(), user.getId());

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
        mediaMovieFileMapper.update(null, new LambdaUpdateWrapper<MediaMovieFile>()
                .eq(MediaMovieFile::getId, file.getId()).set(MediaMovieFile::getDurationMs, null));
        MediaPlaybackInfoVo vo2 = mediaPlaybackService.getPlaybackInfo(movie.getId(), user.getId());
        assertNull(vo2.getEffectiveBitRate());
    }

    /**
     * 播放进度记录到标题级新行：电影行（一部电影多版本共享）、集行、other 行各自记录；
     * 续播通过 last_play_file_id 定位具体版本文件（多版本各挂各的外部字幕）。
     */
    @Test
    void shouldRecordProgressToTitleRowsAndResumeByLastPlayFileId() {
        UserVo user = prepareUserWithStorageSpace();
        // 电影：两版本 + 各版本独立字幕（1080p 简体 / 4K English）
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        fileService.upload(buildFile("沙丘.1080p.mkv", "video".getBytes()), user.getId(), dune.getId(), null);
        fileService.upload(buildFile("沙丘.1080p.chs.srt", SRT_CONTENT.getBytes(StandardCharsets.UTF_8)),
                user.getId(), dune.getId(), null);
        fileService.upload(buildFile("沙丘.4K.mkv", "video".getBytes()), user.getId(), dune.getId(), null);
        fileService.upload(buildFile("沙丘.4K.eng.srt", SRT_CONTENT.getBytes(StandardCharsets.UTF_8)),
                user.getId(), dune.getId(), null);
        MediaDirectory movieDir = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(movieDir.getId());

        MediaMovie movie = querySingleMovie(movieDir.getId());
        List<MediaMovieFile> versions = queryMovieFiles(movie.getId());
        assertEquals(2, versions.size());
        MediaMovieFile v1 = versions.stream().filter(f -> f.getFileNodeId().equals(fileNodeIdByName("沙丘.1080p.mkv")))
                .findFirst().orElseThrow();
        MediaMovieFile v2 = versions.stream().filter(f -> f.getFileNodeId().equals(fileNodeIdByName("沙丘.4K.mkv")))
                .findFirst().orElseThrow();

        // 第一次播放：进度记到电影行，last_play_file_id 定位最早版本（v1）
        mediaItemService.updateProgress(movie.getId(), progressDto(5000L), user.getId());
        MediaMovie afterFirstPlay = mediaMovieMapper.selectById(movie.getId());
        assertEquals(5000L, afterFirstPlay.getProgressMs());
        assertEquals(v1.getId(), afterFirstPlay.getLastPlayFileId());

        // 续播：按 last_play_file_id 定位 v1，字幕列表只含 v1 的简体字幕
        MediaPlaybackInfoVo resumeV1 = mediaPlaybackService.getPlaybackInfo(movie.getId(), user.getId());
        assertEquals(5000L, resumeV1.getProgressMs());
        assertEquals(1, resumeV1.getSubtitles().size());
        assertEquals("简体", resumeV1.getSubtitles().get(0).getLabel());

        // 切换到 v2（手动指定版本）后继续播放：进度共享更新，last_play_file_id 指向 v2
        movie.setLastPlayFileId(v2.getId());
        mediaMovieMapper.updateById(movie);
        mediaItemService.updateProgress(movie.getId(), progressDto(9000L), user.getId());
        MediaMovie afterSecondPlay = mediaMovieMapper.selectById(movie.getId());
        assertEquals(9000L, afterSecondPlay.getProgressMs());
        assertEquals(v2.getId(), afterSecondPlay.getLastPlayFileId());
        MediaPlaybackInfoVo resumeV2 = mediaPlaybackService.getPlaybackInfo(movie.getId(), user.getId());
        assertEquals(9000L, resumeV2.getProgressMs());
        assertEquals(1, resumeV2.getSubtitles().size());
        assertEquals("English", resumeV2.getSubtitles().get(0).getLabel());
    }

    /**
     * 集进度记录到集行；其他库进度记录到 other 行（文件级本身）。
     */
    @Test
    void shouldRecordProgressToEpisodeAndOtherRows() {
        UserVo user = prepareUserWithStorageSpace();
        // 电视剧
        FileNodeVo tvRoot = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo series = createFolder(user.getId(), tvRoot.getId(), "剧甲");
        FileNodeVo season = createFolder(user.getId(), series.getId(), "Season 1");
        fileService.upload(buildFile("剧甲.S01E01.mkv", "video".getBytes()), user.getId(), season.getId(), null);
        MediaDirectory tvDir = createDirectory(user.getId(), tvRoot.getId(), "tv");
        mediaScanService.scan(tvDir.getId());
        MediaEpisode episode = mediaEpisodeMapper.selectList(null).getFirst();

        mediaItemService.updateProgress(episode.getId(), progressDto(3000L), user.getId());
        MediaEpisode after = mediaEpisodeMapper.selectById(episode.getId());
        assertEquals(3000L, after.getProgressMs());
        assertNotNull(after.getLastPlayFileId());
        MediaPlaybackInfoVo episodeVo = mediaPlaybackService.getPlaybackInfo(episode.getId(), user.getId());
        assertEquals(3000L, episodeVo.getProgressMs());

        // 其他库
        FileNodeVo otherRoot = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "其他");
        fileService.upload(buildFile("素材.mkv", "video".getBytes()), user.getId(), otherRoot.getId(), null);
        MediaDirectory otherDir = createDirectory(user.getId(), otherRoot.getId(), "other");
        mediaScanService.scan(otherDir.getId());
        MediaOther other = mediaOtherMapper.selectList(new LambdaQueryWrapper<MediaOther>()
                .eq(MediaOther::getDirectoryId, otherDir.getId())).getFirst();

        mediaItemService.updateProgress(other.getId(), progressDto(2000L), user.getId());
        assertEquals(2000L, mediaOtherMapper.selectById(other.getId()).getProgressMs());
        MediaPlaybackInfoVo otherVo = mediaPlaybackService.getPlaybackInfo(other.getId(), user.getId());
        assertEquals(2000L, otherVo.getProgressMs());
    }

    /**
     * 外部 srt 字幕经 ffmpeg 转为 webvtt 并缓存（ext_{fileNodeId}.vtt），二次读取复用缓存。
     */
    @Test
    void shouldConvertExternalSrtToVttAndReuseCache() throws Exception {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        fileService.upload(buildFile("沙丘.mkv", "video".getBytes()), user.getId(), dune.getId(), null);
        fileService.upload(buildFile("沙丘.zh.srt", SRT_CONTENT.getBytes(StandardCharsets.UTF_8)),
                user.getId(), dune.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());

        MediaMovie movie = querySingleMovie(directory.getId());
        MediaSubtitle subtitle = querySubtitle(queryMovieFile(movie.getId()).getId());

        Path vtt = mediaPlaybackService.extractExternalSubtitle(movie.getId(), subtitle.getId(), user.getId());
        assertTrue(Files.exists(vtt));
        assertEquals("ext_" + subtitle.getFileNodeId() + ".vtt", vtt.getFileName().toString());
        String content = Files.readString(vtt, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("WEBVTT"));
        assertTrue(content.contains("你好，世界"));

        // 二次读取命中缓存，返回同一路径
        Path cached = mediaPlaybackService.extractExternalSubtitle(movie.getId(), subtitle.getId(), user.getId());
        assertEquals(vtt, cached);
    }

    /**
     * 本地 vtt 字幕原样返回字节，不做转换与缓存。
     */
    @Test
    void shouldReturnLocalVttDirectly() throws Exception {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        fileService.upload(buildFile("沙丘.mkv", "video".getBytes()), user.getId(), dune.getId(), null);
        String vttContent = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n你好\n";
        fileService.upload(buildFile("沙丘.vtt", vttContent.getBytes(StandardCharsets.UTF_8)),
                user.getId(), dune.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());

        MediaMovie movie = querySingleMovie(directory.getId());
        MediaSubtitle subtitle = querySubtitle(queryMovieFile(movie.getId()).getId());

        Path path = mediaPlaybackService.extractExternalSubtitle(movie.getId(), subtitle.getId(), user.getId());
        assertEquals(vttContent, Files.readString(path, StandardCharsets.UTF_8));
        // 原样返回用户空间内的原文件，而不是系统空间缓存
        assertEquals("沙丘.vtt", path.getFileName().toString());
    }

    /**
     * 字幕记录不属于该明细行时拒绝读取。
     */
    @Test
    void shouldRejectSubtitleNotBelongingToFileRow() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo movieA = createFolder(user.getId(), movieFolder.getId(), "沙丘A");
        fileService.upload(buildFile("沙丘A.mkv", "video".getBytes()), user.getId(), movieA.getId(), null);
        fileService.upload(buildFile("沙丘A.zh.srt", SRT_CONTENT.getBytes(StandardCharsets.UTF_8)),
                user.getId(), movieA.getId(), null);
        FileNodeVo movieB = createFolder(user.getId(), movieFolder.getId(), "沙丘B");
        fileService.upload(buildFile("沙丘B.mkv", "video".getBytes()), user.getId(), movieB.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());

        MediaMovie movieArow = queryMovieByFolder(directory.getId(), movieA.getId());
        MediaMovie movieBrow = queryMovieByFolder(directory.getId(), movieB.getId());
        MediaMovieFile fileA = queryMovieFile(movieArow.getId());
        MediaSubtitle subtitle = querySubtitle(fileA.getId());

        assertThrows(BusinessException.class,
                () -> mediaPlaybackService.extractExternalSubtitle(movieBrow.getId(), subtitle.getId(), user.getId()));
        assertThrows(BusinessException.class,
                () -> mediaPlaybackService.extractExternalSubtitle(movieArow.getId(), "nonexistent0", user.getId()));
    }

    private com.fleyx.jcloud.model.dto.MediaProgressUpdateDto progressDto(long progressMs) {
        com.fleyx.jcloud.model.dto.MediaProgressUpdateDto dto =
                new com.fleyx.jcloud.model.dto.MediaProgressUpdateDto();
        dto.setProgressMs(progressMs);
        return dto;
    }

    private String fileNodeIdByName(String fileName) {
        return fileMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getName, fileName)).getId();
    }

    private MediaMovie querySingleMovie(String directoryId) {
        return mediaMovieMapper.selectOne(new LambdaQueryWrapper<MediaMovie>()
                .eq(MediaMovie::getDirectoryId, directoryId));
    }

    private MediaMovie queryMovieByFolder(String directoryId, String folderNodeId) {
        return mediaMovieMapper.selectOne(new LambdaQueryWrapper<MediaMovie>()
                .eq(MediaMovie::getDirectoryId, directoryId)
                .eq(MediaMovie::getFolderNodeId, folderNodeId));
    }

    private MediaMovieFile queryMovieFile(String movieId) {
        List<MediaMovieFile> files = mediaMovieFileMapper.selectList(
                new LambdaQueryWrapper<MediaMovieFile>().eq(MediaMovieFile::getMovieId, movieId));
        assertEquals(1, files.size());
        return files.get(0);
    }

    private List<MediaMovieFile> queryMovieFiles(String movieId) {
        return mediaMovieFileMapper.selectList(
                new LambdaQueryWrapper<MediaMovieFile>().eq(MediaMovieFile::getMovieId, movieId));
    }

    private MediaSubtitle querySubtitle(String fileRowId) {
        List<MediaSubtitle> subtitles = mediaSubtitleMapper.selectList(
                new LambdaQueryWrapper<MediaSubtitle>().eq(MediaSubtitle::getFileId, fileRowId));
        assertEquals(1, subtitles.size());
        return subtitles.get(0);
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

    private FileNodeVo createFolder(String userId, String parentId, String name) {
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(parentId);
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
