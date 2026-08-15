package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaDirectorySourceMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSubtitleMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaSubtitle;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部字幕扫描关联测试（issue #19 起按文件明细行关联，file_id 指向明细行/other 行）：
 * 同目录前缀匹配关联、重扫重建（后加/删除字幕）、文件清理联动删除；
 * 覆盖其他库（other 行）、电影库（电影文件明细行）、电视库（集文件明细行）。
 */
@Transactional
class MediaSubtitleScanTest extends MediaScanTestBase {

    @Autowired
    private MediaScanService mediaScanService;

    @Autowired
    private MediaDirectoryService mediaDirectoryService;

    @Autowired
    private MediaDirectoryMapper mediaDirectoryMapper;

    @Autowired
    private MediaDirectorySourceMapper mediaDirectorySourceMapper;

    @Autowired
    private MediaOtherMapper mediaOtherMapper;

    @Autowired
    private MediaMovieMapper mediaMovieMapper;

    @Autowired
    private MediaMovieFileMapper mediaMovieFileMapper;

    @Autowired
    private MediaEpisodeMapper mediaEpisodeMapper;

    @Autowired
    private MediaEpisodeFileMapper mediaEpisodeFileMapper;

    @Autowired
    private MediaSubtitleMapper mediaSubtitleMapper;

    @Autowired
    private FileMapper fileMapper;

    @MockitoBean
    private MediaScrapeService mediaScrapeService;

    /**
     * 其他库：同目录前缀匹配关联到 other 行（file_id = other 行 ID），
     * Movie.chs.srt、Movie.eng.default.srt 关联到 Movie.mkv，Movie2.srt 不误配，
     * 子目录中的同名字幕不关联；重扫后后加/删除的字幕关联正确重建。
     */
    @Test
    void shouldLinkAndRebuildExternalSubtitles() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "其他");
        fileService.upload(buildFile("Movie.mkv"), user.getId(), folder.getId(), null);
        FileNodeVo chs = fileService.upload(buildFile("Movie.chs.srt"), user.getId(), folder.getId(), null);
        fileService.upload(buildFile("Movie.eng.default.srt"), user.getId(), folder.getId(), null);
        fileService.upload(buildFile("Movie2.srt"), user.getId(), folder.getId(), null);
        FileNodeVo subFolder = createFolder(user.getId(), folder.getId(), "子目录");
        fileService.upload(buildFile("Movie.jpn.srt"), user.getId(), subFolder.getId(), null);

        MediaDirectory directory = createDirectory(user.getId(), folder.getId(), "other");
        mediaScanService.scan(directory.getId());

        Map<String, MediaSubtitle> byLabel = subtitlesOf(directory.getId()).stream()
                .collect(Collectors.toMap(MediaSubtitle::getLabel, Function.identity()));
        assertEquals(2, byLabel.size());
        assertEquals("简体", byLabel.get("简体").getLabel());
        assertFalse(byLabel.get("简体").getIsDefault());
        assertEquals("srt", byLabel.get("简体").getFormat());
        assertEquals("English", byLabel.get("English").getLabel());
        assertTrue(byLabel.get("English").getIsDefault());
        // 关联对象是 other 行（file_id 指向 other 行 ID）
        assertTrue(subtitlesOf(directory.getId()).stream()
                .allMatch(s -> mediaOtherMapper.selectById(s.getFileId()) != null));

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
     * 电影库：字幕按电影文件明细行关联（file_id = t_media_movie_file 明细行 ID），
     * 多版本各挂各的明细行。
     */
    @Test
    void shouldLinkSubtitlesToMovieFileRows() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        fileService.upload(buildFile("沙丘.1080p.mkv"), user.getId(), dune.getId(), null);
        fileService.upload(buildFile("沙丘.1080p.chs.srt"), user.getId(), dune.getId(), null);
        fileService.upload(buildFile("沙丘.4K.mkv"), user.getId(), dune.getId(), null);
        fileService.upload(buildFile("沙丘.4K.eng.srt"), user.getId(), dune.getId(), null);

        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());

        MediaMovie movie = mediaMovieMapper.selectOne(new LambdaQueryWrapper<MediaMovie>()
                .eq(MediaMovie::getDirectoryId, directory.getId()));
        List<MediaSubtitle> subtitles = mediaSubtitleMapper.selectList(new LambdaQueryWrapper<MediaSubtitle>()
                .in(MediaSubtitle::getFileId, mediaMovieFileMapper.selectList(
                                new LambdaQueryWrapper<com.fleyx.jcloud.model.po.MediaMovieFile>()
                                        .eq(com.fleyx.jcloud.model.po.MediaMovieFile::getMovieId, movie.getId()))
                        .stream().map(com.fleyx.jcloud.model.po.MediaMovieFile::getId).toList()));
        assertEquals(2, subtitles.size());
        // 每个字幕记录挂在对应版本的明细行上
        for (MediaSubtitle subtitle : subtitles) {
            assertTrue(mediaMovieFileMapper.selectById(subtitle.getFileId()) != null);
        }
        assertTrue(subtitles.stream().anyMatch(s -> "简体".equals(s.getLabel())));
        assertTrue(subtitles.stream().anyMatch(s -> "English".equals(s.getLabel())));
    }

    /**
     * 电视库：字幕按集文件明细行关联（file_id = t_media_episode_file 明细行 ID）。
     */
    @Test
    void shouldLinkSubtitlesToEpisodeFileRows() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvRoot = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo series = createFolder(user.getId(), tvRoot.getId(), "剧甲");
        FileNodeVo season = createFolder(user.getId(), series.getId(), "Season 1");
        fileService.upload(buildFile("剧甲.S01E01.mkv"), user.getId(), season.getId(), null);
        fileService.upload(buildFile("剧甲.S01E01.chs.srt"), user.getId(), season.getId(), null);
        fileService.upload(buildFile("剧甲.S01E02.mkv"), user.getId(), season.getId(), null);
        fileService.upload(buildFile("剧甲.S01E02.jpn.srt"), user.getId(), season.getId(), null);

        MediaDirectory directory = createDirectory(user.getId(), tvRoot.getId(), "tv");
        mediaScanService.scan(directory.getId());

        List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(null);
        assertEquals(2, episodes.size());
        List<String> episodeFileIds = mediaEpisodeFileMapper.selectList(
                        new LambdaQueryWrapper<com.fleyx.jcloud.model.po.MediaEpisodeFile>()
                                .in(com.fleyx.jcloud.model.po.MediaEpisodeFile::getEpisodeId,
                                        episodes.stream().map(MediaEpisode::getId).toList()))
                .stream().map(com.fleyx.jcloud.model.po.MediaEpisodeFile::getId).toList();
        List<MediaSubtitle> subtitles = mediaSubtitleMapper.selectList(new LambdaQueryWrapper<MediaSubtitle>()
                .in(MediaSubtitle::getFileId, episodeFileIds));
        assertEquals(2, subtitles.size());
        for (MediaSubtitle subtitle : subtitles) {
            assertTrue(mediaEpisodeFileMapper.selectById(subtitle.getFileId()) != null);
        }
        assertTrue(subtitles.stream().anyMatch(s -> "简体".equals(s.getLabel())));
        assertTrue(subtitles.stream().anyMatch(s -> "日语".equals(s.getLabel())));
    }

    /**
     * 视频文件消失后 other 行被清理，其外部字幕记录一并删除。
     */
    @Test
    void shouldDeleteSubtitlesWhenItemFileRemoved() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "其他");
        FileNodeVo video = fileService.upload(buildFile("Movie.mkv"), user.getId(), folder.getId(), null);
        fileService.upload(buildFile("Movie.srt"), user.getId(), folder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), folder.getId(), "other");
        mediaScanService.scan(directory.getId());
        List<MediaSubtitle> before = subtitlesOf(directory.getId());
        assertEquals(1, before.size());
        String subtitleId = before.get(0).getId();

        fileMapper.deleteById(video.getId());
        mediaScanService.scan(directory.getId());
        // 直接断言字幕行本身已删除（不依赖 other 行反查，避免“other 行没了即断言空”的假通过）
        assertNull(mediaSubtitleMapper.selectById(subtitleId));
    }

    /**
     * 媒体库删除时，其文件明细行的外部字幕记录一并删除。
     */
    @Test
    void shouldDeleteSubtitlesWhenDirectoryDeleted() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "其他");
        fileService.upload(buildFile("Movie.mkv"), user.getId(), folder.getId(), null);
        fileService.upload(buildFile("Movie.srt"), user.getId(), folder.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), folder.getId(), "other");
        mediaScanService.scan(directory.getId());
        List<MediaSubtitle> before = subtitlesOf(directory.getId());
        assertEquals(1, before.size());
        String subtitleId = before.get(0).getId();

        mediaDirectoryService.delete(directory.getId(), user.getId());
        // 直接断言字幕行本身已删除（不依赖 other 行反查，媒体库删除时 other 行一并清理）
        assertNull(mediaSubtitleMapper.selectById(subtitleId));
    }

    /**
     * 位图外挂：.sup 单文件直接关联；.idx+.sub 必须同目录同主名成对才关联（记录指向 .idx 节点、format=idx），
     * 单独 .idx、单独 .sub、成对但分属不同目录、成对但与视频主名不匹配均不产生关联。
     */
    @Test
    void shouldLinkBitmapSubtitlesWithIdxSubPairing() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "其他");
        fileService.upload(buildFile("Movie.mkv"), user.getId(), folder.getId(), null);
        FileNodeVo sup = fileService.upload(buildFile("Movie.zh.sup"), user.getId(), folder.getId(), null);
        FileNodeVo idx = fileService.upload(buildFile("Movie.zh.default.idx"), user.getId(), folder.getId(), null);
        fileService.upload(buildFile("Movie.zh.default.sub"), user.getId(), folder.getId(), null);
        // 单独 .idx 缺同目录 .sub：不关联
        fileService.upload(buildFile("Alone.idx"), user.getId(), folder.getId(), null);
        // 单独 .sub：永远不产生关联（不识别，亦回避 MicroDVD 文本格式同名嗅探）
        fileService.upload(buildFile("Orphan.sub"), user.getId(), folder.getId(), null);
        // .idx 与同主名 .sub 分属不同目录：不关联
        FileNodeVo subFolder = createFolder(user.getId(), folder.getId(), "子目录");
        fileService.upload(buildFile("Split.idx"), user.getId(), folder.getId(), null);
        fileService.upload(buildFile("Split.sub"), user.getId(), subFolder.getId(), null);
        // 成对但与视频主名不匹配：不关联
        fileService.upload(buildFile("Other.idx"), user.getId(), folder.getId(), null);
        fileService.upload(buildFile("Other.sub"), user.getId(), folder.getId(), null);

        MediaDirectory directory = createDirectory(user.getId(), folder.getId(), "other");
        mediaScanService.scan(directory.getId());

        List<MediaSubtitle> subtitles = subtitlesOf(directory.getId());
        assertEquals(2, subtitles.size());
        Map<String, MediaSubtitle> byFormat = subtitles.stream()
                .collect(Collectors.toMap(MediaSubtitle::getFormat, Function.identity()));
        MediaSubtitle supRecord = byFormat.get("sup");
        assertEquals("sup", supRecord.getFormat());
        assertEquals("简体", supRecord.getLabel());
        assertFalse(supRecord.getIsDefault());
        assertEquals(sup.getId(), supRecord.getFileNodeId());
        MediaSubtitle idxRecord = byFormat.get("idx");
        assertEquals("idx", idxRecord.getFormat());
        assertEquals("简体", idxRecord.getLabel());
        assertTrue(idxRecord.getIsDefault());
        // 成对关联记录指向 .idx 节点（同目录 .sub 由 ffmpeg 自动读取）
        assertEquals(idx.getId(), idxRecord.getFileNodeId());
    }

    private List<MediaSubtitle> subtitlesOf(String directoryId) {
        List<String> fileRowIds = mediaOtherMapper.selectList(new LambdaQueryWrapper<com.fleyx.jcloud.model.po.MediaOther>()
                        .eq(com.fleyx.jcloud.model.po.MediaOther::getDirectoryId, directoryId))
                .stream().map(com.fleyx.jcloud.model.po.MediaOther::getId).toList();
        if (fileRowIds.isEmpty()) {
            return List.of();
        }
        return mediaSubtitleMapper.selectList(
                new LambdaQueryWrapper<MediaSubtitle>().in(MediaSubtitle::getFileId, fileRowIds));
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
        return new MockMultipartFile("file", name, "video/mp4", "video".getBytes());
    }

}
