package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaDirectorySourceMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaSubtitleMapper;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.bo.TranscodeSessionParams;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaSubtitle;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.support.MediaProbeSupport;
import com.fleyx.jcloud.service.support.TranscodeCommandBuilder;
import com.fleyx.jcloud.service.support.TranscodeSessionManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内嵌位图字幕轨烧录的会话创建契约（工单 02）：
 * subtitleIndex 序号校验（文本轨拒绝、不存在拒绝）与位图序号透传到 TranscodeRequest。
 * 探测结果以 mock 提供——真实 ffprobe 无法就地生成 PGS 等位图轨样本，mock 可同时覆盖文本/位图/不存在三态。
 */
@Transactional
class MediaPlaybackBurnInServiceTest extends MediaScanTestBase {

    @MockitoBean
    private MediaProbeSupport mediaProbeSupport;

    @MockitoBean
    private TranscodeSessionManager transcodeSessionManager;

    @MockitoBean
    private MediaScrapeService mediaScrapeService;

    @MockitoBean
    private RemoteFileService remoteFileService;

    @Autowired
    private MediaPlaybackService mediaPlaybackService;

    @Autowired
    private MediaScanService mediaScanService;

    @Autowired
    private MediaMovieMapper mediaMovieMapper;

    @Autowired
    private MediaMovieFileMapper mediaMovieFileMapper;

    @Autowired
    private MediaDirectoryMapper mediaDirectoryMapper;

    @Autowired
    private MediaDirectorySourceMapper mediaDirectorySourceMapper;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private MediaSubtitleMapper mediaSubtitleMapper;

    /**
     * 探测结果：0 号文本轨（subrip）+ 1 号位图轨（hdmv_pgs_subtitle），轨道序号与 ffmpeg 0:s:N 一致。
     */
    private MediaProbeResult probeResultWithBitmapTrack() {
        return new MediaProbeResult(6_000L, "matroska", "h264", "aac", 1280, 720, 1_000_000L,
                List.of(new MediaProbeResult.Track(0, "aac", "chi", null, true)),
                List.of(new MediaProbeResult.Track(0, "subrip", "chi", "简体", true),
                        new MediaProbeResult.Track(1, "hdmv_pgs_subtitle", "jpn", "PGS", false)));
    }

    /**
     * 位图序号透传：subtitleIndex 命中位图轨时放行，会话请求携带该序号（强制转码由命令构建器保证）。
     */
    @Test
    void shouldPassThroughBitmapSubtitleIndex() {
        UserVo user = prepareUserWithStorageSpace().user();
        MediaMovie movie = setupMovie(user);
        when(mediaProbeSupport.probe(any(Path.class))).thenReturn(probeResultWithBitmapTrack());

        mediaPlaybackService.createTranscodeSession(movie.getId(), user.getId(), null,
                new TranscodeSessionParams(0L, null, 1, null, null, null, false));

        ArgumentCaptor<TranscodeCommandBuilder.TranscodeRequest> captor =
                ArgumentCaptor.forClass(TranscodeCommandBuilder.TranscodeRequest.class);
        verify(transcodeSessionManager).createSession(eq(user.getId()), captor.capture());
        assertEquals(1, captor.getValue().subtitleIndex());
        assertNotNull(captor.getValue().localPath());
    }

    /**
     * 文本轨序号拒绝：文本字幕走 WebVTT 链路，不接受烧录，返回参数错误且不创建会话。
     */
    @Test
    void shouldRejectTextSubtitleIndex() {
        UserVo user = prepareUserWithStorageSpace().user();
        MediaMovie movie = setupMovie(user);
        when(mediaProbeSupport.probe(any(Path.class))).thenReturn(probeResultWithBitmapTrack());

        BusinessException e = assertThrows(BusinessException.class, () ->
                mediaPlaybackService.createTranscodeSession(movie.getId(), user.getId(), null,
                        new TranscodeSessionParams(0L, null, 0, null, null, null, false)));
        assertEquals("文本字幕无需烧录", e.getMessage());
        verify(transcodeSessionManager, never()).createSession(any(), any());
    }

    /**
     * 不存在序号拒绝：subtitleIndex 超出字幕轨列表返回参数错误。
     */
    @Test
    void shouldRejectNonexistentSubtitleIndex() {
        UserVo user = prepareUserWithStorageSpace().user();
        MediaMovie movie = setupMovie(user);
        when(mediaProbeSupport.probe(any(Path.class))).thenReturn(probeResultWithBitmapTrack());

        BusinessException e = assertThrows(BusinessException.class, () ->
                mediaPlaybackService.createTranscodeSession(movie.getId(), user.getId(), null,
                        new TranscodeSessionParams(0L, null, 2, null, null, null, false)));
        assertEquals("字幕轨不存在", e.getMessage());
    }

    /**
     * probe 失败回退为空轨列表：任何烧录序号都命中「字幕轨不存在」，不会带病放行。
     */
    @Test
    void shouldRejectSubtitleIndexWhenProbeFails() {
        UserVo user = prepareUserWithStorageSpace().user();
        MediaMovie movie = setupMovie(user);
        when(mediaProbeSupport.probe(any(Path.class)))
                .thenThrow(new com.fleyx.jcloud.common.exception.SystemException(
                        com.fleyx.jcloud.common.enums.ResultCode.SYSTEM_ERROR, "probe 失败"));

        BusinessException e = assertThrows(BusinessException.class, () ->
                mediaPlaybackService.createTranscodeSession(movie.getId(), user.getId(), null,
                        new TranscodeSessionParams(0L, null, 0, null, null, null, false)));
        assertEquals("字幕轨不存在", e.getMessage());
    }

    /**
     * 外挂位图字幕（本地 .sup）透传：会话请求装入 externalSubtitlePath 物理路径，远程流字段为空。
     */
    @Test
    void shouldPassThroughLocalBitmapExternalSubtitle() {
        UserVo user = prepareUserWithStorageSpace().user();
        MediaMovie movie = setupMovieWithSubtitle(user, "沙丘.chs.sup");
        MediaSubtitle subtitle = querySubtitle(queryMovieFile(movie.getId()).getId());
        when(mediaProbeSupport.probe(any(Path.class))).thenReturn(probeResultWithBitmapTrack());

        mediaPlaybackService.createTranscodeSession(movie.getId(), user.getId(), null,
                new TranscodeSessionParams(0L, null, null, subtitle.getId(), null, null, false));

        ArgumentCaptor<TranscodeCommandBuilder.TranscodeRequest> captor =
                ArgumentCaptor.forClass(TranscodeCommandBuilder.TranscodeRequest.class);
        verify(transcodeSessionManager).createSession(eq(user.getId()), captor.capture());
        assertNotNull(captor.getValue().externalSubtitlePath());
        assertEquals("沙丘.chs.sup", captor.getValue().externalSubtitlePath().getFileName().toString());
        assertNull(captor.getValue().externalSubtitleStream());
    }

    /**
     * 文本外挂拒绝：srt 字幕走 WebVTT 链路，不接受烧录，返回参数错误且不创建会话。
     */
    @Test
    void shouldRejectTextExternalSubtitle() {
        UserVo user = prepareUserWithStorageSpace().user();
        MediaMovie movie = setupMovieWithSubtitle(user, "沙丘.chs.srt");
        MediaSubtitle subtitle = querySubtitle(queryMovieFile(movie.getId()).getId());
        when(mediaProbeSupport.probe(any(Path.class))).thenReturn(probeResultWithBitmapTrack());

        BusinessException e = assertThrows(BusinessException.class, () ->
                mediaPlaybackService.createTranscodeSession(movie.getId(), user.getId(), null,
                        new TranscodeSessionParams(0L, null, null, subtitle.getId(), null, null, false)));
        assertEquals("文本字幕无需烧录", e.getMessage());
        verify(transcodeSessionManager, never()).createSession(any(), any());
    }

    /**
     * 字幕记录不属于当前文件明细行时拒绝（归属校验同 extractExternalSubtitle）。
     */
    @Test
    void shouldRejectExternalSubtitleNotBelongingToFileRow() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo movieA = createFolder(user.getId(), movieFolder.getId(), "沙丘A");
        upload(user.getId(), movieA.getId(), "沙丘A.mkv");
        fileService.upload(buildFile("沙丘A.chs.sup", "sup".getBytes(StandardCharsets.UTF_8)),
                user.getId(), movieA.getId(), null);
        FileNodeVo movieB = createFolder(user.getId(), movieFolder.getId(), "沙丘B");
        upload(user.getId(), movieB.getId(), "沙丘B.mkv");
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());

        MediaMovie movieArow = queryMovieByFolder(directory.getId(), movieA.getId());
        MediaMovie movieBrow = queryMovieByFolder(directory.getId(), movieB.getId());
        MediaSubtitle subtitle = querySubtitle(queryMovieFile(movieArow.getId()).getId());
        when(mediaProbeSupport.probe(any(Path.class))).thenReturn(probeResultWithBitmapTrack());

        BusinessException e = assertThrows(BusinessException.class, () ->
                mediaPlaybackService.createTranscodeSession(movieBrow.getId(), user.getId(), null,
                        new TranscodeSessionParams(0L, null, null, subtitle.getId(), null, null, false)));
        assertEquals("字幕不存在", e.getMessage());
        verify(transcodeSessionManager, never()).createSession(any(), any());
    }

    /**
     * 内嵌轨序号与外部字幕 ID 互斥：同时传递返回参数错误，不创建会话。
     */
    @Test
    void shouldRejectBothEmbeddedAndExternalSubtitle() {
        UserVo user = prepareUserWithStorageSpace().user();
        MediaMovie movie = setupMovieWithSubtitle(user, "沙丘.chs.sup");
        MediaSubtitle subtitle = querySubtitle(queryMovieFile(movie.getId()).getId());
        when(mediaProbeSupport.probe(any(Path.class))).thenReturn(probeResultWithBitmapTrack());

        BusinessException e = assertThrows(BusinessException.class, () ->
                mediaPlaybackService.createTranscodeSession(movie.getId(), user.getId(), null,
                        new TranscodeSessionParams(0L, null, 1, subtitle.getId(), null, null, false)));
        assertEquals("内嵌与外部字幕只能二选一", e.getMessage());
        verify(transcodeSessionManager, never()).createSession(any(), any());
    }

    /**
     * 远程 idx 外挂缺同目录同主名 .sub：VobSub 字幕拒绝烧录（报错提示，不静默降级）。
     */
    @Test
    void shouldRejectRemoteIdxWithoutSubFile() {
        UserVo user = prepareUserWithStorageSpace().user();
        MediaMovie movie = setupMovie(user);
        FileNode idxNode = remoteSubtitleNode(user, movie.getFolderNodeId(), "沙丘.chs.idx");
        MediaSubtitle subtitle = insertSubtitleRecord(queryMovieFile(movie.getId()).getId(), idxNode.getId(), "idx");
        when(mediaProbeSupport.probe(any(Path.class))).thenReturn(probeResultWithBitmapTrack());

        BusinessException e = assertThrows(BusinessException.class, () ->
                mediaPlaybackService.createTranscodeSession(movie.getId(), user.getId(), null,
                        new TranscodeSessionParams(0L, null, null, subtitle.getId(), null, null, false)));
        assertEquals("VobSub 字幕缺少 .sub 文件", e.getMessage());
        verify(transcodeSessionManager, never()).createSession(any(), any());
    }

    /**
     * 远程 idx 成对：.idx/.sub 双流装入 externalSubtitleStream（主名一致供物化同主名落地），
     * 下载通道各取一次（复用现有远程下载通道，不在 service 层落地）。
     */
    @Test
    void shouldAssembleRemoteIdxPairStreams() {
        UserVo user = prepareUserWithStorageSpace().user();
        MediaMovie movie = setupMovie(user);
        String folderId = movie.getFolderNodeId();
        FileNode idxNode = remoteSubtitleNode(user, folderId, "沙丘.chs.idx");
        remoteSubtitleNode(user, folderId, "沙丘.chs.sub");
        MediaSubtitle subtitle = insertSubtitleRecord(queryMovieFile(movie.getId()).getId(), idxNode.getId(), "idx");
        when(mediaProbeSupport.probe(any(Path.class))).thenReturn(probeResultWithBitmapTrack());
        when(remoteFileService.download(any(FileNode.class), eq(user.getId())))
                .thenReturn(new FileDownloadResult("x", new ByteArrayInputStream(new byte[]{1}),
                        "application/octet-stream", 1L));

        mediaPlaybackService.createTranscodeSession(movie.getId(), user.getId(), null,
                new TranscodeSessionParams(0L, null, null, subtitle.getId(), null, null, false));

        ArgumentCaptor<TranscodeCommandBuilder.TranscodeRequest> captor =
                ArgumentCaptor.forClass(TranscodeCommandBuilder.TranscodeRequest.class);
        verify(transcodeSessionManager).createSession(eq(user.getId()), captor.capture());
        TranscodeCommandBuilder.ExternalSubtitleStream stream = captor.getValue().externalSubtitleStream();
        assertNotNull(stream);
        assertEquals("沙丘.chs", stream.mainName());
        assertEquals("idx", stream.extension());
        assertNull(captor.getValue().externalSubtitlePath());
        // 下载通道为按需拉取（物化时才调用），会话管理器 mock 不触发；手动拉取两流验证装配指向下载服务
        assertNotNull(stream.stream().get());
        assertNotNull(stream.subStream().get());
        verify(remoteFileService, times(2)).download(any(FileNode.class), eq(user.getId()));
    }

    private MediaMovie setupMovie(UserVo user) {
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        upload(user.getId(), dune.getId(), "沙丘.mkv");
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());
        return mediaMovieMapper.selectOne(new LambdaQueryWrapper<MediaMovie>()
                .eq(MediaMovie::getDirectoryId, directory.getId()));
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
     * 上传主视频 + 一个外部字幕文件并扫描（字幕记录由扫描重建生成）。
     */
    private MediaMovie setupMovieWithSubtitle(UserVo user, String subtitleName) {
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        upload(user.getId(), dune.getId(), "沙丘.mkv");
        fileService.upload(buildFile(subtitleName, "sub".getBytes(StandardCharsets.UTF_8)),
                user.getId(), dune.getId(), null);
        MediaDirectory directory = createDirectory(user.getId(), movieFolder.getId(), "movie");
        mediaScanService.scan(directory.getId());
        return mediaMovieMapper.selectOne(new LambdaQueryWrapper<MediaMovie>()
                .eq(MediaMovie::getDirectoryId, directory.getId()));
    }

    /**
     * 手动插入远程字幕文件节点（不落物理盘，下载走 mock 的 RemoteFileService）。
     */
    private FileNode remoteSubtitleNode(UserVo user, String parentId, String name) {
        FileNode node = new FileNode();
        node.setUserId(user.getId());
        node.setParentId(parentId);
        node.setName(name);
        node.setType(FileNodeConstants.TYPE_FILE);
        node.setSourceType(FileNodeConstants.SOURCE_REMOTE);
        fileMapper.insert(node);
        return node;
    }

    private MediaSubtitle insertSubtitleRecord(String fileRowId, String fileNodeId, String format) {
        MediaSubtitle subtitle = new MediaSubtitle();
        subtitle.setFileId(fileRowId);
        subtitle.setFileNodeId(fileNodeId);
        subtitle.setFormat(format);
        mediaSubtitleMapper.insert(subtitle);
        return subtitle;
    }

    private MediaMovie queryMovieByFolder(String directoryId, String folderNodeId) {
        return mediaMovieMapper.selectOne(new LambdaQueryWrapper<MediaMovie>()
                .eq(MediaMovie::getDirectoryId, directoryId)
                .eq(MediaMovie::getFolderNodeId, folderNodeId));
    }

    private MediaMovieFile queryMovieFile(String movieId) {
        return mediaMovieFileMapper.selectList(new LambdaQueryWrapper<MediaMovieFile>()
                .eq(MediaMovieFile::getMovieId, movieId)).get(0);
    }

    private MediaSubtitle querySubtitle(String fileRowId) {
        return mediaSubtitleMapper.selectOne(new LambdaQueryWrapper<MediaSubtitle>()
                .eq(MediaSubtitle::getFileId, fileRowId));
    }

    private MultipartFile buildFile(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }
}
