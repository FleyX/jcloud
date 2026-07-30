package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.MediaDirectorySaveDto;
import com.fleyx.jcloud.model.dto.MediaDirectoryUpdateDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.MediaDirectoryVo;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 媒体库管理服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaDirectoryServiceTest {

    @Autowired
    private FileService fileService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private FileOperationService fileOperationService;

    @Autowired
    private MediaDirectoryService mediaDirectoryService;

    @Autowired
    private MediaItemMapper mediaItemMapper;

    @Autowired
    private MediaSeriesMapper mediaSeriesMapper;

    @MockitoBean
    private MediaScanService mediaScanService;

    @TempDir
    Path tempDir;

    /**
     * 创建媒体库：来源目录落库并在视图中返回（含文件夹名称与来源类型），提交后触发首次扫描。
     */
    @Test
    void shouldCreateLibraryWithSources() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo folderA = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影A");
        FileNodeVo folderB = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影B");

        MediaDirectoryVo vo = mediaDirectoryService.save(buildSaveDto(List.of(folderA.getId(), folderB.getId())), user.getId());

        assertEquals(2, vo.getSources().size());
        assertEquals(folderA.getId(), vo.getSources().get(0).getFileNodeId());
        assertEquals("电影A", vo.getSources().get(0).getFolderName());
        assertEquals(FileNodeConstants.SOURCE_LOCAL, vo.getSources().get(0).getSourceType());
        assertEquals("电影B", vo.getSources().get(1).getFolderName());
        triggerAfterCommit();
        verify(mediaScanService).submitScan(vo.getId(), user.getId());
    }

    /**
     * 显示名为空时取第一个来源目录文件夹名。
     */
    @Test
    void shouldDefaultNameToFirstSourceFolder() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "我的电影");
        MediaDirectorySaveDto dto = buildSaveDto(List.of(folder.getId()));
        dto.setName(null);

        MediaDirectoryVo vo = mediaDirectoryService.save(dto, user.getId());

        assertEquals("我的电影", vo.getName());
    }

    /**
     * 同一媒体库内来源目录不允许重复。
     */
    @Test
    void shouldThrowWhenSourceDuplicatedInRequest() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        MediaDirectorySaveDto dto = buildSaveDto(List.of(folder.getId(), folder.getId()));

        assertThrows(BusinessException.class, () -> mediaDirectoryService.save(dto, user.getId()));
    }

    /**
     * 来源目录必须是当前用户的文件夹。
     */
    @Test
    void shouldThrowWhenSourceNotFolder() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo file = fileService.upload(
                new MockMultipartFile("file", "a.mp4", "video/mp4", "video".getBytes()),
                user.getId(), FileNodeConstants.ROOT_ID, null);
        MediaDirectorySaveDto dto = buildSaveDto(List.of(file.getId()));

        assertThrows(BusinessException.class, () -> mediaDirectoryService.save(dto, user.getId()));
    }

    /**
     * 同一文件夹不能属于该用户的其他媒体库（跨库查重）。
     */
    @Test
    void shouldThrowWhenSourceUsedByOtherLibrary() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        mediaDirectoryService.save(buildSaveDto(List.of(folder.getId())), user.getId());

        assertThrows(BusinessException.class,
                () -> mediaDirectoryService.save(buildSaveDto(List.of(folder.getId())), user.getId()));
    }

    /**
     * 同一媒体库内来源目录之间不允许祖先/后代重叠。
     */
    @Test
    void shouldThrowWhenSourcesOverlap() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo parent = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "影视");
        FileNodeVo child = createFolder(user.getId(), parent.getId(), "电影");

        assertThrows(BusinessException.class,
                () -> mediaDirectoryService.save(buildSaveDto(List.of(parent.getId(), child.getId())), user.getId()));
    }

    /**
     * 媒体类型创建后不可修改：不一致抛业务异常，不传或传相同值放行。
     */
    @Test
    void shouldThrowWhenMediaTypeChangedOnUpdate() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        MediaDirectoryVo vo = mediaDirectoryService.save(buildSaveDto(List.of(folder.getId())), user.getId());

        MediaDirectoryUpdateDto changed = buildUpdateDto(vo.getId(), List.of(folder.getId()));
        changed.setMediaType("tv");
        assertThrows(BusinessException.class, () -> mediaDirectoryService.update(changed, user.getId()));

        MediaDirectoryUpdateDto same = buildUpdateDto(vo.getId(), List.of(folder.getId()));
        mediaDirectoryService.update(same, user.getId());
        MediaDirectoryUpdateDto absent = buildUpdateDto(vo.getId(), List.of(folder.getId()));
        absent.setMediaType(null);
        mediaDirectoryService.update(absent, user.getId());
        assertEquals("movie", mediaDirectoryService.list(user.getId()).getFirst().getMediaType());
    }

    /**
     * 增删来源目录：中断当前任务并强制全量重扫；被移除来源目录下的条目（含播放进度）删除，孤儿剧清理。
     */
    @Test
    void shouldRescanAndCleanItemsWhenSourcesChanged() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo folderA = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视A");
        FileNodeVo folderB = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视B");
        MediaDirectoryVo vo = mediaDirectoryService.save(buildSaveDto(List.of(folderA.getId(), folderB.getId())), user.getId());
        String sourceIdA = vo.getSources().get(0).getId();
        String sourceIdB = vo.getSources().get(1).getId();

        MediaSeries series = new MediaSeries();
        series.setUserId(user.getId());
        series.setSeriesName("测试剧");
        mediaSeriesMapper.insert(series);
        insertItem(user.getId(), vo.getId(), sourceIdA, "filenodesa01", 100L, null);
        insertItem(user.getId(), vo.getId(), sourceIdB, "filenodesb01", 200L, series.getId());

        MediaDirectoryUpdateDto dto = buildUpdateDto(vo.getId(), List.of(folderA.getId()));
        MediaDirectoryVo updated = mediaDirectoryService.update(dto, user.getId());

        // 中断当前任务 + 强制全量重扫（事务提交后）
        verify(mediaScanService).requestCancel(vo.getId());
        triggerAfterCommit();
        verify(mediaScanService).submitScan(vo.getId(), user.getId(), true);

        // 被移除来源目录下的条目删除，保留来源的条目与播放进度保留
        List<MediaItem> items = mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                .eq(MediaItem::getDirectoryId, vo.getId()));
        assertEquals(1, items.size());
        assertEquals(sourceIdA, items.getFirst().getSourceId());
        assertEquals(100L, items.getFirst().getProgressMs());
        // 孤儿剧被清理
        assertNull(mediaSeriesMapper.selectById(series.getId()));
        // 视图只保留一个来源目录
        assertEquals(1, updated.getSources().size());
        assertEquals(folderA.getId(), updated.getSources().getFirst().getFileNodeId());
    }

    /**
     * 仅修改名称/cron 不触发中断与重扫。
     */
    @Test
    void shouldNotRescanWhenOnlyNameChanged() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        MediaDirectoryVo vo = mediaDirectoryService.save(buildSaveDto(List.of(folder.getId())), user.getId());
        int syncCountBefore = TransactionSynchronizationManager.getSynchronizations().size();

        MediaDirectoryUpdateDto dto = buildUpdateDto(vo.getId(), List.of(folder.getId()));
        dto.setName("新名称");
        mediaDirectoryService.update(dto, user.getId());

        verify(mediaScanService, never()).requestCancel(anyString());
        // 未注册新的事务提交后动作（不触发重扫）
        assertEquals(syncCountBefore, TransactionSynchronizationManager.getSynchronizations().size());
        assertEquals("新名称", mediaDirectoryService.list(user.getId()).getFirst().getName());
    }

    /**
     * 删除媒体库：级联删除来源目录与全部条目，清理孤儿剧。
     */
    @Test
    void shouldDeleteLibraryCascade() {
        UserVo user = prepareUserWithStorageSpace();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        MediaDirectoryVo vo = mediaDirectoryService.save(buildSaveDto(List.of(folder.getId())), user.getId());
        MediaSeries series = new MediaSeries();
        series.setUserId(user.getId());
        series.setSeriesName("测试剧");
        mediaSeriesMapper.insert(series);
        insertItem(user.getId(), vo.getId(), vo.getSources().getFirst().getId(), "filenodes001", 0L, series.getId());

        mediaDirectoryService.delete(vo.getId(), user.getId());

        verify(mediaScanService).requestCancel(vo.getId());
        assertEquals(0, mediaItemMapper.selectCount(new LambdaQueryWrapper<MediaItem>()
                .eq(MediaItem::getDirectoryId, vo.getId())));
        assertNull(mediaSeriesMapper.selectById(series.getId()));
        assertEquals(0, mediaDirectoryService.list(user.getId()).size());
    }

    /**
     * 手动触发事务提交后的回调（测试事务不会真正提交）。
     */
    private void triggerAfterCommit() {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
    }

    private void insertItem(String userId, String directoryId, String sourceId, String fileNodeId,
                            Long progressMs, String seriesId) {
        MediaItem item = new MediaItem();
        item.setUserId(userId);
        item.setDirectoryId(directoryId);
        item.setSourceId(sourceId);
        item.setFileNodeId(fileNodeId);
        item.setItemType("episode");
        item.setMatchStatus("unmatched");
        item.setProgressMs(progressMs);
        item.setSeriesId(seriesId);
        mediaItemMapper.insert(item);
    }

    private MediaDirectorySaveDto buildSaveDto(List<String> sourceFileNodeIds) {
        MediaDirectorySaveDto dto = new MediaDirectorySaveDto();
        dto.setSourceFileNodeIds(sourceFileNodeIds);
        dto.setName("测试媒体库");
        dto.setMediaType("movie");
        return dto;
    }

    private MediaDirectoryUpdateDto buildUpdateDto(String id, List<String> sourceFileNodeIds) {
        MediaDirectoryUpdateDto dto = new MediaDirectoryUpdateDto();
        dto.setId(id);
        dto.setSourceFileNodeIds(sourceFileNodeIds);
        dto.setName("测试媒体库");
        dto.setMediaType("movie");
        return dto;
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
