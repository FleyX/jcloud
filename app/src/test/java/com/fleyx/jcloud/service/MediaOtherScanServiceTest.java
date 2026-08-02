package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.MediaScanStatus;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaDirectorySourceMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileRenameDto;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
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
 * 其他媒体库新模型扫描与查询服务测试（issue #19，ADR 0021）。
 * <p>
 * 每个视频文件一行落 t_media_other（文件级，文件节点锚定，任意层级目录）、两阶段 reconcile、
 * 即时删除与批次扫描时间三道闸清理、多库隔离、网格列表与详情查询走新表。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaOtherScanServiceTest {

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
    private MediaOtherMapper mediaOtherMapper;

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
     * 落表与锚定：来源目录任意层级（含根散文件与子目录）下的每个视频文件一行落 t_media_other，
     * 锚 = 视频文件节点；条目名为文件名，ffprobe 探测结果与文件变更哈希落库，批次扫描时间写入；
     * 重扫幂等（锚定 upsert 不重复建行）；非视频文件不入库。
     */
    @Test
    void shouldScanOtherLibraryIntoNewTableWithFileAnchors() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo root = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "其他");
        FileNodeVo clip1 = upload(user.getId(), root.getId(), "教学视频.01.mkv");
        FileNodeVo sub = createFolder(user.getId(), root.getId(), "子目录");
        FileNodeVo clip2 = upload(user.getId(), sub.getId(), "演示.02.mp4");
        FileNodeVo deep = createFolder(user.getId(), sub.getId(), "更深");
        FileNodeVo clip3 = upload(user.getId(), deep.getId(), "素材.03.mkv");
        upload(user.getId(), root.getId(), "说明.txt");

        MediaDirectory directory = createOtherDirectory(user.getId(), root.getId());
        mediaScanService.scan(directory.getId());
        mediaScanService.scan(directory.getId());

        List<MediaOther> rows = mediaOtherMapper.selectList(null);
        assertEquals(3, rows.size());
        MediaOther row1 = rows.stream().filter(r -> r.getFileNodeId().equals(clip1.getId())).findFirst().orElseThrow();
        assertEquals(directory.getId(), row1.getDirectoryId());
        assertEquals(directory.getUserId(), row1.getUserId());
        assertNotNull(row1.getSourceId());
        assertEquals("教学视频.01.mkv", row1.getName());
        assertEquals(3_600_000L, row1.getDurationMs());
        assertEquals(1920, row1.getWidth());
        assertEquals("h264", row1.getVideoCodec());
        assertNotNull(row1.getFileHash());
        assertEquals(0L, row1.getProgressMs());
        assertNotNull(row1.getScanTime());
        MediaOther row2 = rows.stream().filter(r -> r.getFileNodeId().equals(clip2.getId())).findFirst().orElseThrow();
        MediaOther row3 = rows.stream().filter(r -> r.getFileNodeId().equals(clip3.getId())).findFirst().orElseThrow();
        assertEquals("演示.02.mp4", row2.getName());
        assertEquals("素材.03.mkv", row3.getName());
        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
        verify(mediaScrapeService, atLeastOnce()).submitScrape(directory.getId(), user.getId(), false);
    }

    /**
     * 视频文件被删除后重扫：other 行即时删除（锚文件消失，不等批次清理）；文件移动走改挂。
     */
    @Test
    void shouldDeleteOtherRowImmediatelyWhenFileRemoved() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo root = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "其他");
        FileNodeVo keep = upload(user.getId(), root.getId(), "保留.mkv");
        FileNodeVo removed = upload(user.getId(), root.getId(), "删除.mkv");
        MediaDirectory directory = createOtherDirectory(user.getId(), root.getId());
        mediaScanService.scan(directory.getId());
        assertEquals(2, mediaOtherMapper.selectCount(null));

        fileMapper.deleteById(removed.getId());
        mediaScanService.scan(directory.getId());

        assertEquals(1, mediaOtherMapper.selectCount(null));
        assertEquals("保留.mkv", mediaOtherMapper.selectList(null).getFirst().getName());
        assertNotNull(mediaOtherMapper.selectOne(new LambdaQueryWrapper<MediaOther>()
                .eq(MediaOther::getFileNodeId, keep.getId())));
        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
    }

    /**
     * 视频文件重命名后重扫：按锚找到同一 other 行，仅更新条目名，播放进度保留。
     */
    @Test
    void shouldPreserveProgressWhenFileRenamed() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo root = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "其他");
        FileNodeVo video = upload(user.getId(), root.getId(), "旧名.mkv");
        MediaDirectory directory = createOtherDirectory(user.getId(), root.getId());
        mediaScanService.scan(directory.getId());

        MediaOther row = querySingleOther(directory.getId());
        row.setProgressMs(5000L);
        row.setLastPlayTime(LocalDateTime.now());
        mediaOtherMapper.updateById(row);

        rename(user.getId(), video.getId(), "新名.mkv");
        mediaScanService.scan(directory.getId());

        MediaOther after = querySingleOther(directory.getId());
        assertEquals(row.getId(), after.getId());
        assertEquals("新名.mkv", after.getName());
        assertEquals(5000L, after.getProgressMs());
    }

    /**
     * 视频文件跨来源移动后重扫：other 行按锚改挂到新来源（行 ID 与文件锚不变），播放进度保留。
     */
    @Test
    void shouldReanchorOtherRowWhenFileMovedAcrossSources() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo root = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "其他");
        FileNodeVo sourceA = createFolder(user.getId(), root.getId(), "库A");
        FileNodeVo video = upload(user.getId(), sourceA.getId(), "素材.mkv");
        FileNodeVo sourceB = createFolder(user.getId(), root.getId(), "库B");
        MediaDirectory directory = createOtherDirectory(user.getId(), root.getId());
        addSource(directory.getId(), sourceB.getId());
        mediaScanService.scan(directory.getId());

        MediaOther row = querySingleOther(directory.getId());
        row.setProgressMs(5000L);
        mediaOtherMapper.updateById(row);
        MediaDirectorySource sourceArow = mediaDirectorySourceMapper.selectOne(
                new LambdaQueryWrapper<MediaDirectorySource>()
                        .eq(MediaDirectorySource::getDirectoryId, directory.getId())
                        .eq(MediaDirectorySource::getFileNodeId, sourceA.getId()));

        moveFileNode(user.getId(), video.getId(), sourceB.getId());
        mediaScanService.scan(directory.getId());

        MediaOther after = querySingleOther(directory.getId());
        assertEquals(row.getId(), after.getId());
        assertEquals(video.getId(), after.getFileNodeId());
        MediaDirectorySource sourceBrow = mediaDirectorySourceMapper.selectOne(
                new LambdaQueryWrapper<MediaDirectorySource>()
                        .eq(MediaDirectorySource::getDirectoryId, directory.getId())
                        .eq(MediaDirectorySource::getFileNodeId, sourceB.getId()));
        assertEquals(sourceBrow.getId(), after.getSourceId());
        assertEquals(5000L, after.getProgressMs());
    }

    /**
     * 删除来源子树后重扫：批次清理删除消失的 other 行，同库其他来源与多库数据完好。
     */
    @Test
    void shouldBatchCleanupWhenFolderRemoved() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo root = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "其他");
        FileNodeVo keepFolder = createFolder(user.getId(), root.getId(), "保留");
        upload(user.getId(), keepFolder.getId(), "保留.mkv");
        FileNodeVo removedFolder = createFolder(user.getId(), root.getId(), "删除");
        FileNodeVo removedVideo = upload(user.getId(), removedFolder.getId(), "删除.mkv");
        MediaDirectory directory = createOtherDirectory(user.getId(), root.getId());
        mediaScanService.scan(directory.getId());
        assertEquals(2, mediaOtherMapper.selectCount(null));

        purgeSubtree(user.getId(), removedFolder.getId());
        mediaScanService.scan(directory.getId());

        assertEquals(1, mediaOtherMapper.selectCount(null));
        assertNull(mediaOtherMapper.selectOne(new LambdaQueryWrapper<MediaOther>()
                .eq(MediaOther::getFileNodeId, removedVideo.getId())));
        assertNotNull(mediaOtherMapper.selectOne(new LambdaQueryWrapper<MediaOther>()
                .eq(MediaOther::getDirectoryId, directory.getId())));
        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
    }

    /**
     * 来源目录不可达（远程挂载掉线）时扫描记为 PARTIAL，其下条目不被批次清理；
     * 来源出库后完整重扫，合格来源下消失的文件才被清理（三道闸语义）。
     */
    @Test
    void shouldNotCleanupWhenSourceUnreachableOrPartial() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo root = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "其他");
        FileNodeVo sourceA = createFolder(user.getId(), root.getId(), "库A");
        upload(user.getId(), sourceA.getId(), "A.mkv");
        FileNodeVo sourceB = createFolder(user.getId(), root.getId(), "库B");
        FileNodeVo bFile = upload(user.getId(), sourceB.getId(), "B.mkv");

        MediaDirectory directory = createOtherDirectory(user.getId(), sourceA.getId());
        addSource(directory.getId(), sourceB.getId());
        mediaScanService.scan(directory.getId());
        assertEquals(2, mediaOtherMapper.selectCount(null));

        // 来源 A 掉线 + 来源 B 下的文件被删除：本轮扫描 PARTIAL，批次清理两道闸不执行；
        // B 行因文件亲眼消失被即时删除（other 行即文件行），A 行保留
        purgeSubtree(user.getId(), sourceA.getId());
        fileMapper.deleteById(bFile.getId());
        mediaScanService.scan(directory.getId());

        assertEquals(MediaScanStatus.PARTIAL.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
        assertEquals(1, mediaOtherMapper.selectCount(null));
        assertEquals("A.mkv", mediaOtherMapper.selectList(null).getFirst().getName());

        // 来源 A 出库后完整重扫：合格来源 B 下消失的文件被批次清理；来源 A 不结算
        mediaDirectorySourceMapper.delete(new LambdaQueryWrapper<MediaDirectorySource>()
                .eq(MediaDirectorySource::getDirectoryId, directory.getId())
                .eq(MediaDirectorySource::getFileNodeId, sourceA.getId()));
        mediaScanService.scan(directory.getId());

        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
        assertEquals(1, mediaOtherMapper.selectCount(null));
        assertEquals("A.mkv", mediaOtherMapper.selectList(null).getFirst().getName());
    }

    /**
     * 多库隔离：同名文件跨库各建一行（库级归属）；一个库的扫描与清理不影响另一个库。
     */
    @Test
    void shouldIsolateCleanupWithinDirectory() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo root1 = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "其他一");
        FileNodeVo f1 = upload(user.getId(), root1.getId(), "素材.mkv");
        FileNodeVo root2 = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "其他二");
        upload(user.getId(), root2.getId(), "素材.mkv");

        MediaDirectory directory1 = createOtherDirectory(user.getId(), root1.getId());
        MediaDirectory directory2 = createOtherDirectory(user.getId(), root2.getId());
        mediaScanService.scan(directory1.getId());
        mediaScanService.scan(directory2.getId());
        assertEquals(2, mediaOtherMapper.selectCount(null));

        fileMapper.deleteById(f1.getId());
        mediaScanService.scan(directory1.getId());

        assertEquals(0, mediaOtherMapper.selectCount(new LambdaQueryWrapper<MediaOther>()
                .eq(MediaOther::getDirectoryId, directory1.getId())));
        assertEquals(1, mediaOtherMapper.selectCount(new LambdaQueryWrapper<MediaOther>()
                .eq(MediaOther::getDirectoryId, directory2.getId())));
    }

    /**
     * 网格列表与详情走新表：每行一个卡片（文件名、时长、进度），跨库过滤；详情返回文件事实。
     */
    @Test
    void shouldServeGridAndDetailFromNewTable() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo root = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "其他");
        FileNodeVo video = upload(user.getId(), root.getId(), "教学视频.01.mkv");
        upload(user.getId(), root.getId(), "演示.02.mp4");
        MediaDirectory directory = createOtherDirectory(user.getId(), root.getId());
        mediaScanService.scan(directory.getId());

        MediaOther row = mediaOtherMapper.selectOne(new LambdaQueryWrapper<MediaOther>()
                .eq(MediaOther::getFileNodeId, video.getId()));
        row.setProgressMs(5000L);
        mediaOtherMapper.updateById(row);

        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setDirectoryId(directory.getId());
        IPage<MediaItemVo> page = mediaItemService.listOthers(user.getId(), query);
        assertEquals(2L, page.getTotal());
        MediaItemVo card = page.getRecords().stream()
                .filter(v -> v.getFileNodeId().equals(video.getId())).findFirst().orElseThrow();
        assertEquals("other", card.getItemType());
        assertEquals("教学视频.01.mkv", card.getTitle());
        assertEquals("教学视频.01.mkv", card.getFileName());
        assertEquals(3_600_000L, card.getDurationMs());
        assertEquals(5000L, card.getProgressMs());
        assertNotNull(card.getFileNodeId());

        // 关键词过滤与跨库过滤
        MediaPageQueryDto keyword = new MediaPageQueryDto();
        keyword.setKeyword("教学");
        assertEquals(1L, mediaItemService.listOthers(user.getId(), keyword).getTotal());
        MediaPageQueryDto otherQuery = new MediaPageQueryDto();
        otherQuery.setDirectoryId("not-exists-dir");
        assertEquals(0L, mediaItemService.listOthers(user.getId(), otherQuery).getTotal());

        MediaItemDetailVo detail = mediaItemService.getItemDetail(row.getId(), user.getId());
        assertEquals(row.getId(), detail.getId());
        assertEquals("other", detail.getItemType());
        assertEquals("教学视频.01.mkv", detail.getTitle());
        assertEquals(3_600_000L, detail.getDurationMs());
        assertEquals(1920, detail.getWidth());
        assertEquals(1080, detail.getHeight());
        assertEquals("h264", detail.getVideoCodec());
        assertEquals("aac", detail.getAudioCodec());
        assertEquals(5000L, detail.getProgressMs());
    }

    private MediaOther querySingleOther(String directoryId) {
        return mediaOtherMapper.selectOne(new LambdaQueryWrapper<MediaOther>()
                .eq(MediaOther::getDirectoryId, directoryId));
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
     * 直接改文件节点的父节点与物化路径，模拟跨来源移动（绕过两阶段冲突解决流程）。
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

    private void rename(String userId, String nodeId, String newName) {
        FileRenameDto dto = new FileRenameDto();
        dto.setId(nodeId);
        dto.setNewName(newName);
        fileOperationService.rename(dto, userId);
    }

    private MediaDirectory createOtherDirectory(String userId, String folderNodeId) {
        MediaDirectory directory = new MediaDirectory();
        directory.setUserId(userId);
        directory.setName("测试其他库");
        directory.setMediaType("other");
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
