package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaScanStatus;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaDirectorySourceMapper;
import com.fleyx.jcloud.mapper.MediaMetadataV2Mapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileRenameDto;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaMetadataV2;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaMovieVersionVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.support.MediaProbeSupport;
import com.fleyx.jcloud.util.FilePathUtil;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * 电影媒体库新模型扫描与查询服务测试（issue #18，ADR 0021）。
 * <p>
 * 「直接包含视频文件的目录即一部电影」识别（多级中间目录、根散文件丢弃、extras 清单跳过、
 * 电影文件夹内子目录视频不入库、多版本聚合）、文件夹/文件节点锚定 upsert、按电影即时 reconcile、
 * 批次扫描时间三道闸清理、级联删除（连带 t_media_metadata_v2）、海报墙唯一性与详情版本列表。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaMovieScanServiceTest {

    private static final MediaProbeResult PROBE = new MediaProbeResult(
            3_600_000L, "matroska", "h264", "aac", 1920, 1080, null, List.of(), List.of());

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
    private MediaDirectoryMapper mediaDirectoryMapper;

    @Autowired
    private MediaDirectorySourceMapper mediaDirectorySourceMapper;

    @Autowired
    private MediaMovieMapper mediaMovieMapper;

    @Autowired
    private MediaMovieFileMapper mediaMovieFileMapper;

    @Autowired
    private MediaMetadataV2Mapper mediaMetadataV2Mapper;

    @Autowired
    private FileMapper fileMapper;

    @MockitoBean
    private MediaScrapeService mediaScrapeService;

    @MockitoBean
    private MediaProbeSupport mediaProbeSupport;

    @TempDir
    Path tempDir;

    @BeforeEach
    void stubProbe() {
        lenient().when(mediaProbeSupport.probe(any(Path.class))).thenReturn(PROBE);
        lenient().when(mediaProbeSupport.probe(any(java.io.InputStream.class))).thenReturn(PROBE);
    }

    /**
     * 落表与锚定：多级中间目录下的电影正确识别为电影行（文件夹锚），目录内多个视频聚合为多版本
     * （明细行文件锚）；来源根散文件丢弃、extras 清单目录（trailers / Behind The Scenes）整棵跳过、
     * 电影文件夹内子目录（非 extras）视频不入库；批次扫描时间写入电影行；重扫幂等（锚定 upsert 不重复建行）。
     */
    @Test
    void shouldScanMovieLibraryIntoNewTablesWithAnchors() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        // 多级中间目录组织
        FileNodeVo actionFolder = createFolder(user.getId(), movieFolder.getId(), "动作");
        FileNodeVo yearFolder = createFolder(user.getId(), actionFolder.getId(), "2024");
        FileNodeVo dune = createFolder(user.getId(), yearFolder.getId(), "沙丘 (2021)");
        upload(user.getId(), dune.getId(), "沙丘.2021.1080p.mkv");
        upload(user.getId(), dune.getId(), "沙丘.2021.4K.mkv");
        FileNodeVo brightSword = createFolder(user.getId(), movieFolder.getId(), "亮剑");
        upload(user.getId(), brightSword.getId(), "亮剑.mkv");
        // 来源根目录下的散文件：丢弃
        upload(user.getId(), movieFolder.getId(), "流浪地球.mkv");
        // extras 清单目录整棵跳过（trailers + 大小写/空格变体的 Behind The Scenes）
        FileNodeVo trailers = createFolder(user.getId(), dune.getId(), "trailers");
        upload(user.getId(), trailers.getId(), "沙丘.预告.mkv");
        FileNodeVo behindTheScenes = createFolder(user.getId(), brightSword.getId(), "Behind The Scenes");
        upload(user.getId(), behindTheScenes.getId(), "亮剑.花絮.mkv");
        // 电影文件夹内子目录（非 extras）的视频不入库：电影文件夹不再向下递归找更多电影
        FileNodeVo subFolder = createFolder(user.getId(), dune.getId(), "子目录");
        upload(user.getId(), subFolder.getId(), "沙丘.删减片段.mkv");

        MediaDirectory directory = createMovieDirectory(user.getId(), movieFolder.getId());
        mediaScanService.scan(directory.getId());
        // 重扫幂等：锚定 upsert 不重复建行
        mediaScanService.scan(directory.getId());

        List<MediaMovie> movies = mediaMovieMapper.selectList(null);
        assertEquals(2, movies.size());
        MediaMovie duneMovie = movies.stream().filter(m -> m.getFolderNodeId().equals(dune.getId()))
                .findFirst().orElseThrow();
        assertEquals(directory.getId(), duneMovie.getDirectoryId());
        assertEquals(directory.getUserId(), duneMovie.getUserId());
        assertNotNull(duneMovie.getSourceId());
        assertEquals("沙丘", duneMovie.getTitle());
        assertEquals(2021, duneMovie.getReleaseYear());
        assertNotNull(duneMovie.getScanTime());
        assertEquals(MediaMatchStatus.UNMATCHED.getCode(), duneMovie.getMatchStatus());
        assertEquals(Boolean.FALSE, duneMovie.getMetadataComplete());
        assertEquals(0L, duneMovie.getProgressMs());
        MediaMovie brightSwordMovie = movies.stream().filter(m -> m.getFolderNodeId().equals(brightSword.getId()))
                .findFirst().orElseThrow();
        assertEquals("亮剑", brightSwordMovie.getTitle());
        assertNull(brightSwordMovie.getReleaseYear());

        // 沙丘聚合 2 个版本（明细行锚 = 视频文件节点），ffprobe 探测结果与文件变更哈希落库
        List<MediaMovieFile> duneFiles = filesOfMovie(duneMovie.getId());
        assertEquals(2, duneFiles.size());
        assertTrue(duneFiles.stream().anyMatch(f -> f.getFileNodeId().equals(
                fileMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                        .eq(FileNode::getName, "沙丘.2021.1080p.mkv")).getId())));
        assertEquals(3_600_000L, duneFiles.getFirst().getDurationMs());
        assertEquals(1920, duneFiles.getFirst().getWidth());
        assertNotNull(duneFiles.getFirst().getFileHash());
        assertEquals(1, filesOfMovie(brightSwordMovie.getId()).size());
        // 散文件/extras/子目录视频均未落表
        assertEquals(3, mediaMovieFileMapper.selectCount(null));
        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
        // 扫描完成后自动提交一次非强制削刮
        verify(mediaScrapeService, atLeastOnce()).submitScrape(directory.getId(), user.getId(), false);
    }

    /**
     * 电影文件夹重命名（非 manual）后重扫：按锚找到同一电影行，仅更新 title/release_year，
     * 匹配状态重置为 unmatched 并清空元数据关联；播放进度保留。
     */
    @Test
    void shouldResetNonManualMatchWhenMovieFolderRenamed() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        upload(user.getId(), dune.getId(), "沙丘.2021.mkv");
        MediaDirectory directory = createMovieDirectory(user.getId(), movieFolder.getId());
        mediaScanService.scan(directory.getId());

        // 模拟削刮完成状态 + 播放进度
        MediaMovie movie = querySingleMovie(directory.getId());
        movie.setMetadataId("metamovie001");
        movie.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
        mediaMovieMapper.updateById(movie);
        movie.setProgressMs(5000L);
        mediaMovieMapper.updateById(movie);

        rename(user.getId(), dune.getId(), "沙丘 (2021)");
        mediaScanService.scan(directory.getId());

        MediaMovie after = querySingleMovie(directory.getId());
        assertEquals(movie.getId(), after.getId());
        assertEquals("沙丘", after.getTitle());
        assertEquals(2021, after.getReleaseYear());
        assertEquals(MediaMatchStatus.UNMATCHED.getCode(), after.getMatchStatus());
        assertNull(after.getMetadataId());
        assertEquals(5000L, after.getProgressMs());
    }

    /**
     * 电影文件夹重命名（manual）后重扫：匹配状态与元数据关联保留，仅标题字段更新。
     */
    @Test
    void shouldKeepManualMatchWhenMovieFolderRenamed() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        upload(user.getId(), dune.getId(), "沙丘.2021.mkv");
        MediaDirectory directory = createMovieDirectory(user.getId(), movieFolder.getId());
        mediaScanService.scan(directory.getId());

        MediaMovie movie = querySingleMovie(directory.getId());
        movie.setMetadataId("metamovie001");
        movie.setMatchStatus(MediaMatchStatus.MANUAL.getCode());
        mediaMovieMapper.updateById(movie);

        rename(user.getId(), dune.getId(), "沙丘 (2021)");
        mediaScanService.scan(directory.getId());

        MediaMovie after = querySingleMovie(directory.getId());
        assertEquals(movie.getId(), after.getId());
        assertEquals(MediaMatchStatus.MANUAL.getCode(), after.getMatchStatus());
        assertEquals("metamovie001", after.getMetadataId());
        assertEquals(2021, after.getReleaseYear());
    }

    /**
     * 电影文件夹跨来源移动后重扫：电影行按锚改挂到新来源（行 ID 与文件夹锚不变），播放进度保留。
     */
    @Test
    void shouldPreserveMovieAndProgressWhenFolderMovedAcrossSources() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo sourceA = createFolder(user.getId(), movieFolder.getId(), "库A");
        FileNodeVo dune = createFolder(user.getId(), sourceA.getId(), "沙丘");
        upload(user.getId(), dune.getId(), "沙丘.2021.mkv");
        FileNodeVo sourceB = createFolder(user.getId(), movieFolder.getId(), "库B");
        MediaDirectory directory = createMovieDirectory(user.getId(), movieFolder.getId());
        addSource(directory.getId(), sourceB.getId());
        mediaScanService.scan(directory.getId());

        MediaMovie movie = querySingleMovie(directory.getId());
        movie.setProgressMs(5000L);
        mediaMovieMapper.updateById(movie);
        MediaDirectorySource sourceArow = mediaDirectorySourceMapper.selectOne(
                new LambdaQueryWrapper<MediaDirectorySource>()
                        .eq(MediaDirectorySource::getDirectoryId, directory.getId())
                        .eq(MediaDirectorySource::getFileNodeId, sourceA.getId()));

        moveFolderNode(user.getId(), dune.getId(), sourceB.getId());
        mediaScanService.scan(directory.getId());

        MediaMovie after = querySingleMovie(directory.getId());
        assertEquals(movie.getId(), after.getId());
        assertEquals(dune.getId(), after.getFolderNodeId());
        MediaDirectorySource sourceBrow = mediaDirectorySourceMapper.selectOne(
                new LambdaQueryWrapper<MediaDirectorySource>()
                        .eq(MediaDirectorySource::getDirectoryId, directory.getId())
                        .eq(MediaDirectorySource::getFileNodeId, sourceB.getId()));
        assertEquals(sourceBrow.getId(), after.getSourceId());
        assertEquals(5000L, after.getProgressMs());
        assertEquals(1, filesOfMovie(after.getId()).size());
    }

    /**
     * 电影文件夹内一个视频文件被删除后重扫：该明细行即时删除，电影行与其他版本保留。
     */
    @Test
    void shouldDeleteMovieFileImmediatelyWhenVideoRemoved() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        upload(user.getId(), dune.getId(), "沙丘.1080p.mkv");
        FileNodeVo removed = upload(user.getId(), dune.getId(), "沙丘.4K.mkv");
        MediaDirectory directory = createMovieDirectory(user.getId(), movieFolder.getId());
        mediaScanService.scan(directory.getId());
        assertEquals(2, filesOfMovie(querySingleMovie(directory.getId()).getId()).size());

        fileMapper.deleteById(removed.getId());
        mediaScanService.scan(directory.getId());

        MediaMovie movie = querySingleMovie(directory.getId());
        assertNotNull(movie);
        assertEquals(1, filesOfMovie(movie.getId()).size());
        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
    }

    /**
     * 删除电影文件夹后重扫：批次清理级联删除电影行/明细行，连带 metadata_v2 行，同库其他电影完好。
     */
    @Test
    void shouldCascadeDeleteWhenMovieFolderRemoved() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        upload(user.getId(), dune.getId(), "沙丘.2021.mkv");
        FileNodeVo otherFolder = createFolder(user.getId(), movieFolder.getId(), "亮剑");
        upload(user.getId(), otherFolder.getId(), "亮剑.mkv");
        MediaDirectory directory = createMovieDirectory(user.getId(), movieFolder.getId());
        mediaScanService.scan(directory.getId());
        assertEquals(2, mediaMovieMapper.selectCount(null));

        MediaMovie removed = mediaMovieMapper.selectOne(new LambdaQueryWrapper<MediaMovie>()
                .eq(MediaMovie::getFolderNodeId, dune.getId()));
        List<MediaMovieFile> removedFiles = filesOfMovie(removed.getId());
        // 模拟削刮写入的元数据（owner 反向指针），验证级联连带删除
        seedMetadata(user.getId(), "movie", removed.getId());

        purgeSubtree(user.getId(), dune.getId());
        mediaScanService.scan(directory.getId());

        assertNull(mediaMovieMapper.selectById(removed.getId()));
        assertEquals(0, mediaMovieFileMapper.selectCount(new LambdaQueryWrapper<MediaMovieFile>()
                .in(MediaMovieFile::getId, removedFiles.stream().map(MediaMovieFile::getId).toList())));
        assertEquals(0, mediaMetadataV2Mapper.selectCount(null));
        // 另一部电影完好
        assertEquals(1, mediaMovieMapper.selectCount(null));
        assertEquals(1, mediaMovieFileMapper.selectCount(null));
        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
    }

    /**
     * 来源目录不可达（远程挂载掉线）时扫描记为 PARTIAL，且其下条目不被批次清理；
     * 恢复完整扫描后，合格来源下消失的电影才被清理，不合格来源（已出库）的电影不结算。
     */
    @Test
    void shouldNotCleanupWhenSourceUnreachableOrPartial() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo sourceA = createFolder(user.getId(), movieFolder.getId(), "库A");
        FileNodeVo dune = createFolder(user.getId(), sourceA.getId(), "沙丘");
        upload(user.getId(), dune.getId(), "沙丘.2021.mkv");
        FileNodeVo sourceB = createFolder(user.getId(), movieFolder.getId(), "库B");
        FileNodeVo brightSword = createFolder(user.getId(), sourceB.getId(), "亮剑");
        upload(user.getId(), brightSword.getId(), "亮剑.mkv");

        MediaDirectory directory = createMovieDirectory(user.getId(), sourceA.getId());
        addSource(directory.getId(), sourceB.getId());
        mediaScanService.scan(directory.getId());
        assertEquals(2, mediaMovieMapper.selectCount(null));

        // 来源 A 掉线 + 来源 B 下的电影被删除：本轮扫描 PARTIAL，两道闸均不清理
        purgeSubtree(user.getId(), sourceA.getId());
        purgeSubtree(user.getId(), brightSword.getId());
        mediaScanService.scan(directory.getId());

        assertEquals(MediaScanStatus.PARTIAL.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
        assertEquals(2, mediaMovieMapper.selectCount(null));

        // 来源 A 出库后完整重扫：合格来源 B 下消失的电影被批次清理；来源 A 不结算（由目录管理负责清空）
        mediaDirectorySourceMapper.delete(new LambdaQueryWrapper<MediaDirectorySource>()
                .eq(MediaDirectorySource::getDirectoryId, directory.getId())
                .eq(MediaDirectorySource::getFileNodeId, sourceA.getId()));
        mediaScanService.scan(directory.getId());

        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
        List<MediaMovie> remaining = mediaMovieMapper.selectList(null);
        assertEquals(1, remaining.size());
        assertEquals("沙丘", remaining.getFirst().getTitle());
    }

    /**
     * 海报墙与详情走新表：一部电影只出现一次（多版本聚合为一张卡片），跨库过滤；
     * 详情返回版本列表（明细行文件信息），进度取自电影行。
     */
    @Test
    void shouldServePosterWallAndDetailFromNewTables() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘 (2021)");
        FileNodeVo v1 = upload(user.getId(), dune.getId(), "沙丘.2021.1080p.mkv");
        upload(user.getId(), dune.getId(), "沙丘.2021.4K.mkv");
        MediaDirectory directory = createMovieDirectory(user.getId(), movieFolder.getId());
        mediaScanService.scan(directory.getId());

        MediaMovie movie = querySingleMovie(directory.getId());
        movie.setProgressMs(5000L);
        movie.setLastPlayTime(LocalDateTime.now());
        mediaMovieMapper.updateById(movie);

        // 海报墙：一部电影一个卡片（多版本不重复），命中进度与匹配状态
        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setDirectoryId(directory.getId());
        IPage<MediaItemVo> page = mediaItemService.listMovies(user.getId(), query);
        assertEquals(1L, page.getTotal());
        MediaItemVo card = page.getRecords().getFirst();
        assertEquals(movie.getId(), card.getId());
        assertEquals("movie", card.getItemType());
        assertEquals("沙丘", card.getTitle());
        assertEquals(MediaMatchStatus.UNMATCHED.getCode(), card.getMatchStatus());
        assertEquals(5000L, card.getProgressMs());
        assertNotNull(card.getLastPlayTime());
        // 跨库过滤：不传 directoryId 同样命中；不存在库则空
        assertEquals(1L, mediaItemService.listMovies(user.getId(), new MediaPageQueryDto()).getTotal());
        MediaPageQueryDto otherQuery = new MediaPageQueryDto();
        otherQuery.setDirectoryId("not-exists-dir");
        assertEquals(0L, mediaItemService.listMovies(user.getId(), otherQuery).getTotal());

        // 详情：版本列表 = 两个明细行（文件事实），进度/标题来自电影行与元数据
        MediaItemDetailVo detail = mediaItemService.getItemDetail(movie.getId(), user.getId());
        assertEquals(movie.getId(), detail.getId());
        assertEquals("movie", detail.getItemType());
        assertEquals("沙丘", detail.getTitle());
        assertEquals(5000L, detail.getProgressMs());
        assertEquals(3_600_000L, detail.getDurationMs());
        assertEquals(2, detail.getVersions().size());
        MediaMovieVersionVo version = detail.getVersions().stream()
                .filter(v -> v.getFileNodeId().equals(v1.getId())).findFirst().orElseThrow();
        assertEquals("沙丘.2021.1080p.mkv", version.getFileName());
        assertEquals(1920, version.getWidth());
        assertEquals(1080, version.getHeight());
        assertEquals("h264", version.getVideoCodec());
        assertEquals("aac", version.getAudioCodec());
        assertEquals("matroska", version.getContainer());
    }

    /**
     * 视频文件跨电影移动后重扫：明细行按锚改挂到目标电影（行 ID 保留），源电影明细减少，无孤儿行。
     */
    @Test
    void shouldReanchorMovieFileWhenMovedAcrossMovies() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        FileNodeVo dune = createFolder(user.getId(), movieFolder.getId(), "沙丘");
        FileNodeVo v1 = upload(user.getId(), dune.getId(), "沙丘.1080p.mkv");
        FileNodeVo moved = upload(user.getId(), dune.getId(), "沙丘.4K.mkv");
        FileNodeVo brightSword = createFolder(user.getId(), movieFolder.getId(), "亮剑");
        upload(user.getId(), brightSword.getId(), "亮剑.mkv");
        MediaDirectory directory = createMovieDirectory(user.getId(), movieFolder.getId());
        mediaScanService.scan(directory.getId());

        MediaMovie duneMovie = queryMovieByFolder(dune.getId());
        MediaMovie brightSwordMovie = queryMovieByFolder(brightSword.getId());
        MediaMovieFile movedRow = filesOfMovie(duneMovie.getId()).stream()
                .filter(f -> f.getFileNodeId().equals(moved.getId())).findFirst().orElseThrow();

        moveFileNode(user.getId(), moved.getId(), brightSword.getId());
        mediaScanService.scan(directory.getId());

        // 明细行改挂到亮剑：ID 与文件锚不变；沙丘剩 1 明细，亮剑有 2 明细
        MediaMovieFile after = mediaMovieFileMapper.selectById(movedRow.getId());
        assertNotNull(after);
        assertEquals(brightSwordMovie.getId(), after.getMovieId());
        assertEquals(moved.getId(), after.getFileNodeId());
        assertEquals(1, filesOfMovie(duneMovie.getId()).size());
        assertEquals(2, filesOfMovie(brightSwordMovie.getId()).size());
        assertEquals(3, mediaMovieFileMapper.selectCount(null));
    }

    /**
     * 多库隔离：同名电影跨库各建一行（库级归属）；一个库的扫描与清理不影响另一个库。
     */
    @Test
    void shouldIsolateCleanupWithinDirectory() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo movieFolder1 = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影一");
        FileNodeVo dune1 = createFolder(user.getId(), movieFolder1.getId(), "沙丘");
        upload(user.getId(), dune1.getId(), "沙丘.2021.mkv");
        FileNodeVo movieFolder2 = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影二");
        FileNodeVo dune2 = createFolder(user.getId(), movieFolder2.getId(), "沙丘");
        upload(user.getId(), dune2.getId(), "沙丘.2021.mkv");

        MediaDirectory directory1 = createMovieDirectory(user.getId(), movieFolder1.getId());
        MediaDirectory directory2 = createMovieDirectory(user.getId(), movieFolder2.getId());
        mediaScanService.scan(directory1.getId());
        mediaScanService.scan(directory2.getId());
        // 同名电影跨库各建一行
        assertEquals(2, mediaMovieMapper.selectCount(null));

        // 库 1 的电影文件夹删除后重扫库 1：仅库 1 数据被清理
        purgeSubtree(user.getId(), dune1.getId());
        mediaScanService.scan(directory1.getId());

        assertEquals(0, mediaMovieMapper.selectCount(new LambdaQueryWrapper<MediaMovie>()
                .eq(MediaMovie::getDirectoryId, directory1.getId())));
        assertEquals(1, mediaMovieMapper.selectCount(new LambdaQueryWrapper<MediaMovie>()
                .eq(MediaMovie::getDirectoryId, directory2.getId())));
        assertEquals(1, mediaMovieFileMapper.selectCount(null));
    }

    private void seedMetadata(String userId, String ownerType, String ownerId) {
        MediaMetadataV2 metadata = new MediaMetadataV2();
        metadata.setUserId(userId);
        metadata.setOwnerType(ownerType);
        metadata.setOwnerId(ownerId);
        metadata.setSource("tmdb");
        metadata.setTitle("测试元数据");
        mediaMetadataV2Mapper.insert(metadata);
    }

    private MediaMovie querySingleMovie(String directoryId) {
        return mediaMovieMapper.selectOne(new LambdaQueryWrapper<MediaMovie>()
                .eq(MediaMovie::getDirectoryId, directoryId));
    }

    private MediaMovie queryMovieByFolder(String folderNodeId) {
        return mediaMovieMapper.selectOne(new LambdaQueryWrapper<MediaMovie>()
                .eq(MediaMovie::getFolderNodeId, folderNodeId));
    }

    private List<MediaMovieFile> filesOfMovie(String movieId) {
        return mediaMovieFileMapper.selectList(new LambdaQueryWrapper<MediaMovieFile>()
                .eq(MediaMovieFile::getMovieId, movieId));
    }

    /**
     * 物理删除文件夹及其整个子树节点（模拟文件夹被删除或远程挂载掉线）。
     */
    private void purgeSubtree(String userId, String folderNodeId) {
        FileNode folder = fileMapper.selectById(folderNodeId);
        if (folder == null) {
            return;
        }
        List<FileNode> descendants = fileMapper.selectByIdPathPrefix(userId, folder.getPath(), folder.getId());
        if (!descendants.isEmpty()) {
            fileMapper.deleteBatchIds(descendants.stream().map(FileNode::getId).toList());
        }
        fileMapper.deleteById(folderNodeId);
    }

    /**
     * 直接改文件节点的父节点与物化路径，模拟跨电影移动（绕过两阶段冲突解决流程）。
     */
    private void moveFileNode(String userId, String fileNodeId, String targetFolderNodeId) {
        FileNode file = fileMapper.selectById(fileNodeId);
        FileNode targetFolder = fileMapper.selectById(targetFolderNodeId);
        FileNode update = new FileNode();
        update.setId(file.getId());
        update.setParentId(targetFolder.getId());
        update.setPath(FilePathUtil.fullIdPath(targetFolder));
        fileMapper.updateById(update);
    }

    /**
     * 直接改文件夹节点的父节点与物化路径，并同步子树所有节点的物化路径前缀（模拟跨来源移动电影文件夹）。
     */
    private void moveFolderNode(String userId, String folderNodeId, String targetParentNodeId) {
        FileNode folder = fileMapper.selectById(folderNodeId);
        FileNode targetParent = fileMapper.selectById(targetParentNodeId);
        String oldPath = folder.getPath();
        String newPath = FilePathUtil.fullIdPath(targetParent);
        List<FileNode> subtree = fileMapper.selectByIdPathPrefix(userId, oldPath, folderNodeId);
        FileNode update = new FileNode();
        update.setId(folder.getId());
        update.setParentId(targetParent.getId());
        update.setPath(newPath);
        fileMapper.updateById(update);
        String oldPrefix = oldPath + FileNodeConstants.PATH_SEPARATOR;
        String newPrefix = newPath + FileNodeConstants.PATH_SEPARATOR;
        for (FileNode node : subtree) {
            if (node.getId().equals(folder.getId())) {
                continue;
            }
            FileNode descendant = new FileNode();
            descendant.setId(node.getId());
            descendant.setPath(newPrefix + node.getPath().substring(oldPrefix.length()));
            fileMapper.updateById(descendant);
        }
    }

    private void rename(String userId, String nodeId, String newName) {
        FileRenameDto dto = new FileRenameDto();
        dto.setId(nodeId);
        dto.setNewName(newName);
        fileOperationService.rename(dto, userId);
    }

    private MediaDirectory createMovieDirectory(String userId, String folderNodeId) {
        MediaDirectory directory = new MediaDirectory();
        directory.setUserId(userId);
        directory.setName("测试电影库");
        directory.setMediaType("movie");
        mediaDirectoryMapper.insert(directory);
        addSource(directory.getId(), folderNodeId);
        return directory;
    }

    private void addSource(String directoryId, String folderNodeId) {
        MediaDirectorySource source = new MediaDirectorySource();
        source.setDirectoryId(directoryId);
        source.setFileNodeId(folderNodeId);
        mediaDirectorySourceMapper.insert(source);
    }

    private FileNodeVo upload(String userId, String parentId, String name) {
        MultipartFile file = new MockMultipartFile("file", name, "video/x-matroska", "video".getBytes());
        return fileService.upload(file, userId, parentId, null);
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
