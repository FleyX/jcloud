package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
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
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
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
import com.fleyx.jcloud.service.support.MediaSubtitleSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 媒体播放服务测试（issue #19 起播放链路切新模型）：
 * 播放信息基于文件明细行事实、进度记录到标题级新行（电影/集/其他）、多版本共享进度且
 * 续播按 last_play_file_id 定位版本、外部字幕按明细行关联、统一字幕列表与实际码率估算、vtt 转换与缓存。
 */
@Transactional
class MediaPlaybackServiceTest extends MediaScanTestBase {

    private static final String SRT_CONTENT = """
            1
            00:00:01,000 --> 00:00:04,000
            你好，世界

            2
            00:00:05,000 --> 00:00:06,000
            第二行
            """;

    @Autowired
    private MediaScanService mediaScanService;

    @Autowired
    private MediaItemService mediaItemService;

    @Autowired
    private MediaPlaybackService mediaPlaybackService;

    @Autowired
    private MediaSubtitleSupport mediaSubtitleSupport;

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
    private MediaSeriesMapper mediaSeriesMapper;

    @Autowired
    private MediaSubtitleMapper mediaSubtitleMapper;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private SystemConfigService systemConfigService;

    @MockitoBean
    private MediaScrapeService mediaScrapeService;

    /**
     * 播放信息装配统一字幕列表：外部字幕默认优先、其余按标签排序；
     * ffprobe 码率缺失时按明细行文件大小与时长估算实际码率；进度取电影标题级行。
     */
    @Test
    void shouldAssembleUnifiedSubtitleListAndEstimateBitRate() {
        UserVo user = prepareUserWithStorageSpace().user();
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

        MediaPlaybackInfoVo vo = mediaPlaybackService.getPlaybackInfo(movie.getId(), user.getId(), null);

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
        MediaPlaybackInfoVo vo2 = mediaPlaybackService.getPlaybackInfo(movie.getId(), user.getId(), null);
        assertNull(vo2.getEffectiveBitRate());
    }

    /**
     * 播放进度记录到标题级新行：电影行（一部电影多版本共享）、集行、other 行各自记录；
     * 续播通过 last_play_file_id 定位具体版本文件（多版本各挂各的外部字幕）。
     */
    @Test
    void shouldRecordProgressToTitleRowsAndResumeByLastPlayFileId() {
        UserVo user = prepareUserWithStorageSpace().user();
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
        // 首播定位与 pickRepresentative 契约同规则（createTime 最小，相同则 id 较小者），
        // 不假设扫描插入顺序（目录文件列举顺序不稳定，曾致本断言偶发失败）
        MediaMovieFile first = versions.stream()
                .min(Comparator.comparing(MediaMovieFile::getCreateTime,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(MediaMovieFile::getId))
                .orElseThrow();
        MediaMovieFile second = first == v1 ? v2 : v1;
        String firstSubtitle = first == v1 ? "简体" : "English";
        String secondSubtitle = first == v1 ? "English" : "简体";

        // 第一次播放：进度记到电影行，last_play_file_id 定位最早版本
        mediaItemService.updateProgress(movie.getId(), progressDto(5000L), user.getId());
        MediaMovie afterFirstPlay = mediaMovieMapper.selectById(movie.getId());
        assertEquals(5000L, afterFirstPlay.getProgressMs());
        assertEquals(first.getId(), afterFirstPlay.getLastPlayFileId());

        // 续播：按 last_play_file_id 定位首播版本，字幕列表只含该版本的外部字幕
        MediaPlaybackInfoVo resumeV1 = mediaPlaybackService.getPlaybackInfo(movie.getId(), user.getId(), null);
        assertEquals(5000L, resumeV1.getProgressMs());
        assertEquals(1, resumeV1.getSubtitles().size());
        assertEquals(firstSubtitle, resumeV1.getSubtitles().get(0).getLabel());

        // 切换到另一版本（手动指定版本）后继续播放：进度共享更新，last_play_file_id 指向切换后版本
        movie.setLastPlayFileId(second.getId());
        mediaMovieMapper.updateById(movie);
        mediaItemService.updateProgress(movie.getId(), progressDto(9000L), user.getId());
        MediaMovie afterSecondPlay = mediaMovieMapper.selectById(movie.getId());
        assertEquals(9000L, afterSecondPlay.getProgressMs());
        assertEquals(second.getId(), afterSecondPlay.getLastPlayFileId());
        MediaPlaybackInfoVo resumeV2 = mediaPlaybackService.getPlaybackInfo(movie.getId(), user.getId(), null);
        assertEquals(9000L, resumeV2.getProgressMs());
        assertEquals(1, resumeV2.getSubtitles().size());
        assertEquals(secondSubtitle, resumeV2.getSubtitles().get(0).getLabel());
    }

    /**
     * 集进度记录到集行；其他库进度记录到 other 行（文件级本身）。
     */
    @Test
    void shouldRecordProgressToEpisodeAndOtherRows() {
        UserVo user = prepareUserWithStorageSpace().user();
        // 电视剧
        FileNodeVo tvRoot = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo series = createFolder(user.getId(), tvRoot.getId(), "剧甲");
        FileNodeVo season = createFolder(user.getId(), series.getId(), "Season 1");
        fileService.upload(buildFile("剧甲.S01E01.mkv", "video".getBytes()), user.getId(), season.getId(), null);
        MediaDirectory tvDir = createDirectory(user.getId(), tvRoot.getId(), "tv");
        mediaScanService.scan(tvDir.getId());
        MediaEpisode episode = mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                .eq(MediaEpisode::getSeriesId,
                        mediaSeriesMapper.selectList(null).stream()
                                .filter(s -> tvDir.getId().equals(s.getDirectoryId()))
                                .findFirst().orElseThrow().getId()))
                .getFirst();

        mediaItemService.updateProgress(episode.getId(), progressDto(3000L), user.getId());
        MediaEpisode after = mediaEpisodeMapper.selectById(episode.getId());
        assertEquals(3000L, after.getProgressMs());
        assertNotNull(after.getLastPlayFileId());
        MediaPlaybackInfoVo episodeVo = mediaPlaybackService.getPlaybackInfo(episode.getId(), user.getId(), null);
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
        MediaPlaybackInfoVo otherVo = mediaPlaybackService.getPlaybackInfo(other.getId(), user.getId(), null);
        assertEquals(2000L, otherVo.getProgressMs());
    }

    /**
     * 外部 srt 字幕经 ffmpeg 转为 webvtt 并缓存（ext_{fileNodeId}.vtt），二次读取复用缓存。
     */
    @Test
    void shouldConvertExternalSrtToVttAndReuseCache() throws Exception {
        // srt→vtt 真实调用 ffmpeg，环境缺失时跳过而非失败
        assumeTrue(isFfmpegAvailable());
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        fileService.upload(buildFile("沙丘.mkv", "video".getBytes()), user.getId(), dune.getId(), null);
        fileService.upload(buildFile("沙丘.zh.srt", SRT_CONTENT.getBytes(StandardCharsets.UTF_8)),
                user.getId(), dune.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());

        MediaMovie movie = querySingleMovie(directory.getId());
        MediaSubtitle subtitle = querySubtitle(queryMovieFile(movie.getId()).getId());

        Path vtt = mediaPlaybackService.extractExternalSubtitle(movie.getId(), subtitle.getId(), 0, user.getId(), null);
        assertTrue(Files.exists(vtt));
        String fileName = vtt.getFileName().toString();
        // 缓存名带内容版本（节点 hash），不再只按文件节点 ID
        assertTrue(fileName.startsWith("ext_" + subtitle.getFileNodeId() + "_"));
        assertTrue(fileName.endsWith(".vtt"));
        String content = Files.readString(vtt, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("WEBVTT"));
        assertTrue(content.contains("你好，世界"));

        // 二次读取命中缓存，返回同一路径
        Path cached = mediaPlaybackService.extractExternalSubtitle(movie.getId(), subtitle.getId(), 0, user.getId(), null);
        assertEquals(vtt, cached);
    }

    /**
     * 非 UTF-8（GBK）编码的外部 srt 字幕回退转 UTF-8 后再经 ffmpeg 转 webvtt，
     * 中文内容正确解码（若未走 GBK 回退，中文将乱码）。
     */
    @Test
    void shouldConvertGbkEncodedSrtToVtt() throws Exception {
        // srt→vtt 真实调用 ffmpeg，环境缺失时跳过而非失败
        assumeTrue(isFfmpegAvailable());
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        fileService.upload(buildFile("沙丘.mkv", "video".getBytes()), user.getId(), dune.getId(), null);
        fileService.upload(buildFile("沙丘.zh.srt", SRT_CONTENT.getBytes(Charset.forName("GBK"))),
                user.getId(), dune.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());

        MediaMovie movie = querySingleMovie(directory.getId());
        MediaSubtitle subtitle = querySubtitle(queryMovieFile(movie.getId()).getId());

        Path vtt = mediaPlaybackService.extractExternalSubtitle(movie.getId(), subtitle.getId(), 0, user.getId(), null);
        assertTrue(Files.exists(vtt));
        String content = Files.readString(vtt, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("WEBVTT"));
        assertTrue(content.contains("你好，世界"));
        assertTrue(content.contains("第二行"));
    }

    /**
     * 统一字幕列表组装（MediaSubtitleSupport.buildSubtitleList）：
     * 内嵌轨在前（按流序号，language - title 拼装、两者缺失回退「字幕 N」），
     * 内嵌位图轨（PGS）项 bitmap=true、内嵌文本轨 bitmap=false，
     * 外部字幕在后（默认优先、再按标签）：文本外挂 bitmap=false，位图外挂（.sup）bitmap=true。
     * 探测层不再过滤位图轨，此处直接喂混合轨列表验证组装行为。
     */
    @Test
    void shouldAssembleUnifiedSubtitleListWithEmbeddedAndExternalTracks() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        fileService.upload(buildFile("沙丘.mkv", "video".getBytes()), user.getId(), dune.getId(), null);
        fileService.upload(buildFile("沙丘.chs.srt", SRT_CONTENT.getBytes(StandardCharsets.UTF_8)),
                user.getId(), dune.getId(), null);
        fileService.upload(buildFile("沙丘.eng.default.srt", SRT_CONTENT.getBytes(StandardCharsets.UTF_8)),
                user.getId(), dune.getId(), null);
        fileService.upload(buildFile("沙丘.cht.sup", "sup".getBytes()), user.getId(), dune.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());

        MediaMovie movie = querySingleMovie(directory.getId());
        MediaMovieFile file = queryMovieFile(movie.getId());
        List<MediaProbeResult.Track> tracks = List.of(
                new MediaProbeResult.Track(0, "subrip", "chi", "简体中文", false),
                new MediaProbeResult.Track(1, "subrip", null, null, false),
                new MediaProbeResult.Track(2, "ass", "eng", "English", true),
                new MediaProbeResult.Track(3, "hdmv_pgs_subtitle", "jpn", "PGS", false));

        List<MediaSubtitleItemVo> subtitles = mediaSubtitleSupport.buildSubtitleList(tracks, file.getId());

        assertEquals(7, subtitles.size());
        // 内嵌轨在前、按流序号
        MediaSubtitleItemVo embedded0 = subtitles.get(0);
        assertEquals("embedded", embedded0.getType());
        assertEquals(0, embedded0.getIndex());
        assertEquals("chi", embedded0.getLanguage());
        assertEquals("chi - 简体中文", embedded0.getLabel());
        assertEquals(Boolean.FALSE, embedded0.getDefaulted());
        assertEquals(Boolean.FALSE, embedded0.getBitmap());
        // language/title 均缺失时回退「字幕 N」
        MediaSubtitleItemVo embedded1 = subtitles.get(1);
        assertEquals("embedded", embedded1.getType());
        assertEquals(1, embedded1.getIndex());
        assertEquals("字幕 2", embedded1.getLabel());
        assertEquals(Boolean.FALSE, embedded1.getBitmap());
        MediaSubtitleItemVo embedded2 = subtitles.get(2);
        assertEquals("embedded", embedded2.getType());
        assertEquals(2, embedded2.getIndex());
        assertEquals("eng - English", embedded2.getLabel());
        assertEquals(Boolean.TRUE, embedded2.getDefaulted());
        assertEquals(Boolean.FALSE, embedded2.getBitmap());
        // 内嵌位图轨：展示名/语言/default 拼装与文本轨一致，仅 bitmap 为 true
        MediaSubtitleItemVo embedded3 = subtitles.get(3);
        assertEquals("embedded", embedded3.getType());
        assertEquals(3, embedded3.getIndex());
        assertEquals("jpn", embedded3.getLanguage());
        assertEquals("jpn - PGS", embedded3.getLabel());
        assertEquals(Boolean.FALSE, embedded3.getDefaulted());
        assertEquals(Boolean.TRUE, embedded3.getBitmap());
        // 外部在后：默认优先、再按标签；文本外挂 bitmap=false，位图外挂（.sup）bitmap=true
        MediaSubtitleItemVo externalDefault = subtitles.get(4);
        assertEquals("external", externalDefault.getType());
        assertEquals("English", externalDefault.getLabel());
        assertEquals(Boolean.TRUE, externalDefault.getDefaulted());
        assertEquals(Boolean.FALSE, externalDefault.getBitmap());
        assertNotNull(externalDefault.getSubtitleId());
        MediaSubtitleItemVo externalSecond = subtitles.get(5);
        assertEquals("external", externalSecond.getType());
        assertEquals("简体", externalSecond.getLabel());
        assertEquals(Boolean.FALSE, externalSecond.getDefaulted());
        assertEquals(Boolean.FALSE, externalSecond.getBitmap());
        MediaSubtitleItemVo externalBitmap = subtitles.get(6);
        assertEquals("external", externalBitmap.getType());
        assertEquals("繁體", externalBitmap.getLabel());
        assertEquals(Boolean.FALSE, externalBitmap.getDefaulted());
        assertEquals(Boolean.TRUE, externalBitmap.getBitmap());
        assertNotNull(externalBitmap.getSubtitleId());
    }

    /**
     * 本地 vtt 字幕原样返回字节，不做转换与缓存。
     */
    @Test
    void shouldReturnLocalVttDirectly() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
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

        Path path = mediaPlaybackService.extractExternalSubtitle(movie.getId(), subtitle.getId(), 0, user.getId(), null);
        assertEquals(vttContent, Files.readString(path, StandardCharsets.UTF_8));
        // 原样返回用户空间内的原文件，而不是系统空间缓存
        assertEquals("沙丘.vtt", path.getFileName().toString());
    }

    /**
     * 字幕记录不属于该明细行时拒绝读取。
     */
    @Test
    void shouldRejectSubtitleNotBelongingToFileRow() {
        UserVo user = prepareUserWithStorageSpace().user();
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
                () -> mediaPlaybackService.extractExternalSubtitle(movieBrow.getId(), subtitle.getId(), 0, user.getId(), null));
        assertThrows(BusinessException.class,
                () -> mediaPlaybackService.extractExternalSubtitle(movieArow.getId(), "nonexistent0", 0, user.getId(), null));
    }

    /**
     * 指定版本播放（issue #21）：versionId 存在时使用该版本文件事实——字幕按版本关联、
     * 流文件名为该版本、播放信息版本 ID 为指定明细行、直放/转码 URL 携带 versionId 定位同一版本。
     */
    @Test
    void shouldPlaySpecifiedVersionWhenVersionIdProvided() {
        UserVo user = prepareUserWithStorageSpace().user();
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
        MediaMovieFile v2 = versions.stream().filter(f -> f.getFileNodeId().equals(fileNodeIdByName("沙丘.4K.mkv")))
                .findFirst().orElseThrow();

        MediaPlaybackInfoVo vo = mediaPlaybackService.getPlaybackInfo(movie.getId(), user.getId(), v2.getId());
        assertEquals(v2.getId(), vo.getVersionId());
        assertEquals(1, vo.getSubtitles().size());
        assertEquals("English", vo.getSubtitles().get(0).getLabel());
        // mkv 容器需转码，转码 URL 携带 versionId 定位同一版本
        assertEquals("transcode", vo.getMode());
        assertTrue(vo.getTranscodeUrl() != null && vo.getTranscodeUrl().contains("versionId=" + v2.getId()));

        // 流使用该版本文件事实（文件名）
        MediaPlaybackService.MediaStreamResult stream =
                mediaPlaybackService.stream(movie.getId(), user.getId(), null, v2.getId());
        assertEquals("沙丘.4K.mkv", stream.fileName());
    }

    /**
     * versionId 不属于该电影时（不存在或属于其他电影）播放/流/转码均抛业务异常；
     * 剧集/其他传入 versionId 忽略不报错，行为与缺省一致。
     */
    @Test
    void shouldRejectVersionNotBelongingToMovieAndIgnoreForEpisode() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo movieA = createFolder(user.getId(), movieFolder.getId(), "沙丘A");
        fileService.upload(buildFile("沙丘A.mkv", "video".getBytes()), user.getId(), movieA.getId(), null);
        FileNodeVo movieB = createFolder(user.getId(), movieFolder.getId(), "沙丘B");
        fileService.upload(buildFile("沙丘B.mkv", "video".getBytes()), user.getId(), movieB.getId(), null);
        MediaDirectory movieDir = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(movieDir.getId());
        MediaMovie movieArow = queryMovieByFolder(movieDir.getId(), movieA.getId());
        MediaMovie movieBrow = queryMovieByFolder(movieDir.getId(), movieB.getId());
        MediaMovieFile fileB = queryMovieFile(movieBrow.getId());

        // 不存在的版本
        assertThrows(BusinessException.class,
                () -> mediaPlaybackService.getPlaybackInfo(movieArow.getId(), user.getId(), "no-such-ver0"));
        // 属于其他电影的版本
        assertThrows(BusinessException.class,
                () -> mediaPlaybackService.getPlaybackInfo(movieArow.getId(), user.getId(), fileB.getId()));
        assertThrows(BusinessException.class,
                () -> mediaPlaybackService.stream(movieArow.getId(), user.getId(), null, fileB.getId()));
        // 转码路径同样在 ffmpeg 启动前完成版本校验
        assertThrows(BusinessException.class,
                () -> mediaPlaybackService.createTranscodeSession(movieArow.getId(), 0L, null, null, null, null,
                        null, false, user.getId(), "no-such-ver0"));

        // 剧集传 versionId 忽略不报错
        FileNodeVo tvRoot = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo series = createFolder(user.getId(), tvRoot.getId(), "剧甲");
        FileNodeVo season = createFolder(user.getId(), series.getId(), "Season 1");
        fileService.upload(buildFile("剧甲.S01E01.mkv", "video".getBytes()), user.getId(), season.getId(), null);
        MediaDirectory tvDir = createDirectory(user.getId(), tvRoot.getId(), "tv");
        mediaScanService.scan(tvDir.getId());
        MediaEpisode episode = mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                .eq(MediaEpisode::getSeriesId,
                        mediaSeriesMapper.selectList(null).stream()
                                .filter(s -> tvDir.getId().equals(s.getDirectoryId()))
                                .findFirst().orElseThrow().getId()))
                .getFirst();
        MediaPlaybackInfoVo episodeVo =
                mediaPlaybackService.getPlaybackInfo(episode.getId(), user.getId(), fileB.getId());
        assertNotNull(episodeVo);
    }

    /**
     * 带 versionId 上报进度（issue #21）：last_play_file_id 记为该版本，
     * 续播按该版本定位（多版本共享进度、字幕按版本关联）。
     */
    @Test
    void shouldRecordProgressWithVersionIdAndResumeOnThatVersion() {
        UserVo user = prepareUserWithStorageSpace().user();
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
        MediaMovieFile v2 = versions.stream().filter(f -> f.getFileNodeId().equals(fileNodeIdByName("沙丘.4K.mkv")))
                .findFirst().orElseThrow();

        // 指定 v2 上报进度：共享进度更新，last_play_file_id 指向 v2
        com.fleyx.jcloud.model.dto.MediaProgressUpdateDto dto = progressDto(9000L);
        dto.setVersionId(v2.getId());
        mediaItemService.updateProgress(movie.getId(), dto, user.getId());

        MediaMovie after = mediaMovieMapper.selectById(movie.getId());
        assertEquals(9000L, after.getProgressMs());
        assertEquals(v2.getId(), after.getLastPlayFileId());

        // 续播：按 last_play_file_id 定位 v2，字幕只含 v2 的 English
        MediaPlaybackInfoVo resume = mediaPlaybackService.getPlaybackInfo(movie.getId(), user.getId(), null);
        assertEquals(v2.getId(), resume.getVersionId());
        assertEquals(1, resume.getSubtitles().size());
        assertEquals("English", resume.getSubtitles().get(0).getLabel());
    }

    /**
     * 外置字幕支持可选时间偏移（转码会话起点）：
     * 偏移非零时从规范 VTT 生成独立偏移结果，不改写规范缓存；
     * 跨越起点的 cue 从 0 秒开始、完全过期的 cue 不输出。
     */
    @Test
    void shouldApplyOffsetToExternalSubtitleAndKeepCanonicalUntouched() throws Exception {
        // 规范 VTT 经 srt 转换生成，真实调用 ffmpeg，环境缺失时跳过而非失败
        assumeTrue(isFfmpegAvailable());
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        fileService.upload(buildFile("沙丘.mkv", "video".getBytes()), user.getId(), dune.getId(), null);
        fileService.upload(buildFile("沙丘.zh.srt", SRT_CONTENT.getBytes(StandardCharsets.UTF_8)),
                user.getId(), dune.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());

        MediaMovie movie = querySingleMovie(directory.getId());
        MediaSubtitle subtitle = querySubtitle(queryMovieFile(movie.getId()).getId());

        Path canonical = mediaPlaybackService.extractExternalSubtitle(movie.getId(), subtitle.getId(), 0, user.getId(), null);
        String canonicalContent = Files.readString(canonical, StandardCharsets.UTF_8);
        assertTrue(canonicalContent.contains("00:01.000 --> 00:04.000"));

        // 偏移 2000ms：首 cue（1s-4s）跨越起点从 0 开始，次 cue（5s-6s）变为 3s-4s
        Path offset = mediaPlaybackService.extractExternalSubtitle(movie.getId(), subtitle.getId(), 2000, user.getId(), null);
        assertNotEquals(canonical, offset);
        String offsetContent = Files.readString(offset, StandardCharsets.UTF_8);
        assertTrue(offsetContent.contains("00:00.000 --> 00:02.000"));
        assertTrue(offsetContent.contains("00:03.000 --> 00:04.000"));
        assertTrue(offsetContent.contains("你好，世界"));
        // 规范缓存未被改写
        assertEquals(canonicalContent, Files.readString(canonical, StandardCharsets.UTF_8));

        // 偏移 4500ms：首 cue（end 4s <= 4500ms）完全过期移除，次 cue 变为 0.5s-1.5s
        Path expired = mediaPlaybackService.extractExternalSubtitle(movie.getId(), subtitle.getId(), 4500, user.getId(), null);
        String expiredContent = Files.readString(expired, StandardCharsets.UTF_8);
        assertFalse(expiredContent.contains("你好，世界"));
        assertTrue(expiredContent.contains("00:00.500 --> 00:01.500"));
    }

    /**
     * 字幕偏移拒绝负值。
     */
    @Test
    void shouldRejectNegativeOffset() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        fileService.upload(buildFile("沙丘.mkv", "video".getBytes()), user.getId(), dune.getId(), null);
        fileService.upload(buildFile("沙丘.zh.srt", SRT_CONTENT.getBytes(StandardCharsets.UTF_8)),
                user.getId(), dune.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());

        MediaMovie movie = querySingleMovie(directory.getId());
        MediaSubtitle subtitle = querySubtitle(queryMovieFile(movie.getId()).getId());

        assertThrows(BusinessException.class,
                () -> mediaPlaybackService.extractExternalSubtitle(movie.getId(), subtitle.getId(), -1, user.getId(), null));
    }

    /**
     * 外置字幕转换缓存按文件内容版本区分：
     * 同一字幕节点改变 hash/大小/最后修改时间后读取到新缓存，而不是旧 VTT；旧缓存遗留不改写。
     */
    @Test
    void shouldGenerateNewCacheWhenContentVersionChanges() throws Exception {
        // srt→vtt 真实调用 ffmpeg，环境缺失时跳过而非失败
        assumeTrue(isFfmpegAvailable());
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        fileService.upload(buildFile("沙丘.mkv", "video".getBytes()), user.getId(), dune.getId(), null);
        fileService.upload(buildFile("沙丘.zh.srt", SRT_CONTENT.getBytes(StandardCharsets.UTF_8)),
                user.getId(), dune.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());

        MediaMovie movie = querySingleMovie(directory.getId());
        MediaSubtitle subtitle = querySubtitle(queryMovieFile(movie.getId()).getId());

        Path vtt1 = mediaPlaybackService.extractExternalSubtitle(movie.getId(), subtitle.getId(), 0, user.getId(), null);
        String vtt1Content = Files.readString(vtt1, StandardCharsets.UTF_8);

        // 模拟内容版本变化：节点 hash/size/最后修改时间变化
        FileNode node = fileMapper.selectById(subtitle.getFileNodeId());
        node.setHash("changed-" + System.nanoTime());
        node.setSize(node.getSize() + 1);
        node.setLastModified(System.currentTimeMillis());
        fileMapper.updateById(node);

        Path vtt2 = mediaPlaybackService.extractExternalSubtitle(movie.getId(), subtitle.getId(), 0, user.getId(), null);
        assertNotEquals(vtt1, vtt2);
        // 旧缓存遗留且内容不变，新缓存按新版本生成
        assertTrue(Files.exists(vtt1));
        assertEquals(vtt1Content, Files.readString(vtt1, StandardCharsets.UTF_8));
        assertTrue(Files.exists(vtt2));
        assertTrue(Files.readString(vtt2, StandardCharsets.UTF_8).startsWith("WEBVTT"));
    }

    /**
     * 电影版本、电视剧集、其他视频均按文件明细行读取外部字幕。
     */
    @Test
    void shouldReadExternalSubtitleForEpisodeAndOtherRows() throws Exception {
        // srt→vtt 真实调用 ffmpeg，环境缺失时跳过而非失败
        assumeTrue(isFfmpegAvailable());
        UserVo user = prepareUserWithStorageSpace().user();
        // 电视剧：字幕挂在集文件明细行
        FileNodeVo tvRoot = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo series = createFolder(user.getId(), tvRoot.getId(), "剧甲");
        FileNodeVo season = createFolder(user.getId(), series.getId(), "Season 1");
        fileService.upload(buildFile("剧甲.S01E01.mkv", "video".getBytes()), user.getId(), season.getId(), null);
        fileService.upload(buildFile("剧甲.S01E01.chs.srt", SRT_CONTENT.getBytes(StandardCharsets.UTF_8)),
                user.getId(), season.getId(), null);
        MediaDirectory tvDir = createDirectory(user.getId(), tvRoot.getId(), "tv");
        mediaScanService.scan(tvDir.getId());
        MediaEpisode episode = mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                .eq(MediaEpisode::getSeriesId,
                        mediaSeriesMapper.selectList(null).stream()
                                .filter(s -> tvDir.getId().equals(s.getDirectoryId()))
                                .findFirst().orElseThrow().getId()))
                .getFirst();
        MediaEpisodeFile episodeFile = mediaEpisodeFileMapper.selectList(
                new LambdaQueryWrapper<MediaEpisodeFile>().eq(MediaEpisodeFile::getEpisodeId, episode.getId())).getFirst();
        MediaSubtitle episodeSubtitle = querySubtitle(episodeFile.getId());
        Path episodeVtt = mediaPlaybackService.extractExternalSubtitle(
                episode.getId(), episodeSubtitle.getId(), 0, user.getId(), null);
        assertTrue(Files.exists(episodeVtt));
        assertTrue(Files.readString(episodeVtt, StandardCharsets.UTF_8).contains("你好，世界"));

        // 其他库：字幕挂在 other 行
        FileNodeVo otherRoot = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "其他");
        fileService.upload(buildFile("素材.mkv", "video".getBytes()), user.getId(), otherRoot.getId(), null);
        fileService.upload(buildFile("素材.chs.srt", SRT_CONTENT.getBytes(StandardCharsets.UTF_8)),
                user.getId(), otherRoot.getId(), null);
        MediaDirectory otherDir = createDirectory(user.getId(), otherRoot.getId(), "other");
        mediaScanService.scan(otherDir.getId());
        MediaOther other = mediaOtherMapper.selectOne(new LambdaQueryWrapper<MediaOther>()
                .eq(MediaOther::getDirectoryId, otherDir.getId()));
        MediaSubtitle otherSubtitle = querySubtitle(other.getId());
        Path otherVtt = mediaPlaybackService.extractExternalSubtitle(
                other.getId(), otherSubtitle.getId(), 0, user.getId(), null);
        assertTrue(Files.exists(otherVtt));
        assertTrue(Files.readString(otherVtt, StandardCharsets.UTF_8).contains("你好，世界"));

        // 电影版本（多版本各挂各字幕）：上一用例覆盖，此处补一个指定版本读取
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
        MediaMovieFile v2 = queryMovieFiles(movie.getId()).stream()
                .filter(f -> f.getFileNodeId().equals(fileNodeIdByName("沙丘.4K.mkv"))).findFirst().orElseThrow();
        MediaSubtitle versionSubtitle = querySubtitle(v2.getId());
        Path versionVtt = mediaPlaybackService.extractExternalSubtitle(
                movie.getId(), versionSubtitle.getId(), 0, user.getId(), v2.getId());
        assertTrue(Files.exists(versionVtt));
        assertTrue(Files.readString(versionVtt, StandardCharsets.UTF_8).contains("你好，世界"));
    }

    private com.fleyx.jcloud.model.dto.MediaProgressUpdateDto progressDto(long progressMs) {
        com.fleyx.jcloud.model.dto.MediaProgressUpdateDto dto =
                new com.fleyx.jcloud.model.dto.MediaProgressUpdateDto();
        dto.setProgressMs(progressMs);
        return dto;
    }

    /**
     * 检测运行环境是否可用 ffmpeg（srt/ass/ssa → vtt 转换依赖它），不可用时相关用例跳过。
     */
    private boolean isFfmpegAvailable() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
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

    private MultipartFile buildFile(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    @Override
    protected void afterSpaceCreated(StorageSpaceVo space) {
        systemConfigService.setValue("system.storage.space.id", String.valueOf(space.getId()));
    }
}
