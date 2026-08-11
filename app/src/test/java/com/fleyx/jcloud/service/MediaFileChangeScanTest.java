package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.constant.StorageConstant;
import com.fleyx.jcloud.common.enums.FileChangeOperation;
import com.fleyx.jcloud.common.event.FileTreeChangedEvent;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaDirectorySourceMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.util.FilePathUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 影视库文件变更自动刷新测试（ADR 0025「文件变更广播」）。
 * <p>
 * 监听器为异步执行且独立读库，本测试类不使用事务回滚（异步线程看不到未提交数据），
 * 测试数据按唯一用户名/媒体库隔离并在用例结束后物理清理。
 * 防抖窗口通过 {@code @TestPropertySource} 缩短为 1 秒，断言使用 Mockito timeout/after 验证器。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "jcloud.media.file-change-debounce-seconds=1")
class MediaFileChangeScanTest {

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private FileOperationService fileOperationService;

    @Autowired
    private MediaDirectoryMapper mediaDirectoryMapper;

    @Autowired
    private MediaDirectorySourceMapper mediaDirectorySourceMapper;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StorageSpaceMapper storageSpaceMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @MockitoBean
    private MediaScanService mediaScanService;

    @TempDir
    Path tempDir;

    /**
     * 本测试类不使用事务回滚（异步线程看不到未提交数据），创建的测试数据按 ID 登记，
     * 每个用例结束后物理删除，避免泄漏污染共享测试库。
     */
    private final List<String> createdUserIds = new ArrayList<>();
    private final List<String> createdDirectoryIds = new ArrayList<>();
    private final List<String> createdSpaceIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdDirectoryIds.forEach(id -> mediaDirectorySourceMapper.delete(
                new LambdaQueryWrapper<MediaDirectorySource>().eq(MediaDirectorySource::getDirectoryId, id)));
        createdDirectoryIds.forEach(mediaDirectoryMapper::deleteById);
        createdDirectoryIds.clear();
        createdUserIds.forEach(id -> {
            fileMapper.delete(new LambdaQueryWrapper<FileNode>().eq(FileNode::getUserId, id));
            userMapper.deleteById(id);
        });
        createdUserIds.clear();
        createdSpaceIds.forEach(storageSpaceMapper::deleteById);
        createdSpaceIds.clear();
    }

    /**
     * 库外事件直接丢弃，不触发任何扫描。
     */
    @Test
    void shouldDropEventOutsideLibrary() throws Exception {
        UserVo user = prepareUserWithStorageSpace();
        FileNode sourceFolder = createLibraryFolder(user.getId(), "库外事件源");
        createLibrary(user.getId(), "电影库", "movie", sourceFolder.getId());

        publishEvent(user.getId(), "child-outside", FileChangeOperation.CREATE, null,
                FileNodeConstants.ROOT_ID + FileNodeConstants.PATH_SEPARATOR + "unrelatedFolder");

        verify(mediaScanService, after(1500).never()).submitScan(anyString(), eq(user.getId()));
    }

    /**
     * 库内事件防抖窗口后触发该库扫描恰好一次。
     */
    @Test
    void shouldSubmitScanForEventInsideLibrary() throws Exception {
        UserVo user = prepareUserWithStorageSpace();
        FileNode sourceFolder = createLibraryFolder(user.getId(), "库内事件源");
        MediaDirectory dir = createLibrary(user.getId(), "电影库", "movie", sourceFolder.getId());

        publishEvent(user.getId(), "child-1", FileChangeOperation.CREATE, null,
                FilePathUtil.fullIdPath(sourceFolder));

        verify(mediaScanService, timeout(5000)).submitScan(eq(dir.getId()), eq(user.getId()));
    }

    /**
     * 连续多条库内事件防抖合并为一次扫描。
     */
    @Test
    void shouldMergeBurstEventsIntoOneScan() throws Exception {
        UserVo user = prepareUserWithStorageSpace();
        FileNode sourceFolder = createLibraryFolder(user.getId(), "防抖合并源");
        MediaDirectory dir = createLibrary(user.getId(), "电视库", "tv", sourceFolder.getId());

        for (int i = 0; i < 5; i++) {
            publishEvent(user.getId(), "child-" + i, FileChangeOperation.CREATE, null,
                    FilePathUtil.fullIdPath(sourceFolder));
        }

        verify(mediaScanService, timeout(5000)).submitScan(eq(dir.getId()), eq(user.getId()));
    }

    /**
     * 来源目录自身被删除（DELETE 旧侧为来源目录自身完整路径）触发该库。
     */
    @Test
    void shouldSubmitScanWhenSourceFolderDeleted() throws Exception {
        UserVo user = prepareUserWithStorageSpace();
        FileNode sourceFolder = createLibraryFolder(user.getId(), "来源删除源");
        MediaDirectory dir = createLibrary(user.getId(), "电影库", "movie", sourceFolder.getId());

        publishEvent(user.getId(), sourceFolder.getId(), FileChangeOperation.DELETE,
                sourceFolder.getPath(), null);

        verify(mediaScanService, timeout(5000)).submitScan(eq(dir.getId()), eq(user.getId()));
    }

    /**
     * 来源目录的祖先被删除（变更节点完整路径是来源目录完整路径的前缀）触发该库。
     */
    @Test
    void shouldSubmitScanWhenSourceAncestorDeleted() throws Exception {
        UserVo user = prepareUserWithStorageSpace();
        FileNode parentFolder = createLibraryFolder(user.getId(), "祖先文件夹");
        FileNode sourceFolder = createChildFolder(user.getId(), "来源子目录", parentFolder.getId());
        MediaDirectory dir = createLibrary(user.getId(), "电影库", "movie", sourceFolder.getId());

        publishEvent(user.getId(), parentFolder.getId(), FileChangeOperation.DELETE,
                parentFolder.getPath(), null);

        verify(mediaScanService, timeout(5000)).submitScan(eq(dir.getId()), eq(user.getId()));
    }

    /**
     * MOVE 出库事件凭库内旧侧触发该库。
     */
    @Test
    void shouldSubmitScanWhenMovedOutOfLibrary() throws Exception {
        UserVo user = prepareUserWithStorageSpace();
        FileNode sourceFolder = createLibraryFolder(user.getId(), "移出库源");
        MediaDirectory dir = createLibrary(user.getId(), "电影库", "movie", sourceFolder.getId());

        publishEvent(user.getId(), "child-1", FileChangeOperation.MOVE,
                FilePathUtil.fullIdPath(sourceFolder),
                FileNodeConstants.ROOT_ID + FileNodeConstants.PATH_SEPARATOR + "outsideFolder");

        verify(mediaScanService, timeout(5000)).submitScan(eq(dir.getId()), eq(user.getId()));
    }

    /**
     * other 类型媒体库同样触发扫描。
     */
    @Test
    void shouldSubmitScanForOtherLibrary() throws Exception {
        UserVo user = prepareUserWithStorageSpace();
        FileNode sourceFolder = createLibraryFolder(user.getId(), "其他库源");
        MediaDirectory dir = createLibrary(user.getId(), "其他库", "other", sourceFolder.getId());

        publishEvent(user.getId(), "child-1", FileChangeOperation.CREATE, null,
                FilePathUtil.fullIdPath(sourceFolder));

        verify(mediaScanService, timeout(5000)).submitScan(eq(dir.getId()), eq(user.getId()));
    }

    // ---------- 工具方法 ----------

    private void publishEvent(String userId, String nodeId, FileChangeOperation operation,
                              String oldPath, String newPath) {
        eventPublisher.publishEvent(new FileTreeChangedEvent(this, operation, userId, nodeId,
                FileNodeConstants.TYPE_FILE, "item", 1L, null, null, oldPath, newPath));
    }

    /**
     * 在根目录下创建文件夹节点作为媒体库来源目录。
     * <p>
     * 创建时该用户尚无媒体库，CREATE 事件被异步监听后读到空列表直接丢弃；
     * {@link #createChildFolder} 内已等待事件处理完成，避免注册媒体库后
     * CREATE 事件与来源目录自身命中（路径相等）在防抖窗口后产生一次多余扫描。
     */
    private FileNode createLibraryFolder(String userId, String name) throws Exception {
        return createChildFolder(userId, name, FileNodeConstants.ROOT_ID);
    }

    private FileNode createChildFolder(String userId, String name, String parentId) throws Exception {
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(parentId);
        dto.setName(name);
        FileNodeVo vo = fileOperationService.createFolder(dto, userId);
        FileNode folder = fileMapper.selectById(vo.getId());
        // 等待本次 CREATE 事件被异步监听处理完（见 createLibraryFolder 注释）
        Thread.sleep(500);
        return folder;
    }

    private MediaDirectory createLibrary(String userId, String name, String mediaType, String fileNodeId) {
        MediaDirectory directory = new MediaDirectory();
        directory.setUserId(userId);
        directory.setName(name);
        directory.setMediaType(mediaType);
        mediaDirectoryMapper.insert(directory);
        createdDirectoryIds.add(directory.getId());

        MediaDirectorySource source = new MediaDirectorySource();
        source.setDirectoryId(directory.getId());
        source.setFileNodeId(fileNodeId);
        mediaDirectorySourceMapper.insert(source);
        return directory;
    }

    private UserVo prepareUserWithStorageSpace() throws Exception {
        Path spacePath = Files.createTempDirectory(tempDir, "media-file-change-space-");
        StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
        spaceDto.setName("media-file-change-space-" + System.nanoTime());
        spaceDto.setPath(spacePath.toString());
        StorageSpaceVo space = storageSpaceService.save(spaceDto);
        createdSpaceIds.add(space.getId());

        UserSaveDto userDto = new UserSaveDto();
        userDto.setUsername("media_change_user_" + System.nanoTime());
        userDto.setPassword("123456");
        userDto.setStorageSpaceId(space.getId());
        userDto.setQuota(1L);
        userDto.setQuotaUnit("GB");
        UserVo user = userService.saveUser(userDto);
        createdUserIds.add(user.getId());
        Files.createDirectories(spacePath.resolve(StorageConstant.FILES_DIR).resolve(user.getUsername()));
        return user;
    }
}
