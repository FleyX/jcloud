package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.FileChangeOperation;
import com.fleyx.jcloud.common.event.FileTreeChangedEvent;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.RecycleRecordMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.TransferTaskMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.bo.TransferItem;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileDeleteDto;
import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.dto.FileExecuteRestoreDto;
import com.fleyx.jcloud.model.dto.FilePermanentDeleteDto;
import com.fleyx.jcloud.model.dto.FileRenameDto;
import com.fleyx.jcloud.model.dto.OperationItemDto;
import com.fleyx.jcloud.model.dto.RestoreItemDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.TransferTask;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.OperationResultVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.FileOperationService;
import com.fleyx.jcloud.service.FileRecycleService;
import com.fleyx.jcloud.service.FileService;
import com.fleyx.jcloud.service.RemoteFileOperationService;
import com.fleyx.jcloud.service.RemoteProtocolAdapter;
import com.fleyx.jcloud.service.impl.RemoteProtocolAdapterFactory;
import com.fleyx.jcloud.service.impl.WebDavFileOperationHelper;
import com.fleyx.jcloud.util.RemoteMountLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件树变更事件测试（ADR 0025）。
 * <p>
 * 集成部分调用真实写操作入口，断言事务提交后发布了正确的 {@link FileTreeChangedEvent}。
 * 本类不使用 {@code @Transactional}（after-commit 在事务回滚时不触发），测试数据按唯一用户
 * 隔离并在用例结束后物理清理，避免泄漏污染共享测试库。
 * WebDAV 与跨来源传输埋点走 Mockito 单测，断言各写方法调用发布辅助。
 */
class FileTreeChangedEventTest extends IntegrationTestBase {

    private static final String TEST_USER_ID = "u1";
    private static final String TEST_USERNAME = "tester";
    private static final String TEST_SPACE_ID = "sp1";

    @Autowired
    private FileService fileService;

    @Autowired
    private FileOperationService fileOperationService;

    @Autowired
    private FileRecycleService fileRecycleService;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private RecycleRecordMapper recycleRecordMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StorageSpaceMapper storageSpaceMapper;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private FileChangeEventSupport fileChangeEventSupport;

    private final List<String> createdUserIds = new ArrayList<>();
    private final List<String> createdSpaceIds = new ArrayList<>();
    private CapturingListener capturingListener;

    @BeforeEach
    void registerCapturingListener() {
        capturingListener = new CapturingListener();
        ((ConfigurableApplicationContext) applicationContext).addApplicationListener(capturingListener);
    }

    @AfterEach
    void cleanup() {
        ((ConfigurableApplicationContext) applicationContext).removeApplicationListener(capturingListener);
        for (String userId : createdUserIds) {
            recycleRecordMapper.delete(new LambdaQueryWrapper<RecycleRecord>().eq(RecycleRecord::getUserId, userId));
            fileMapper.delete(new LambdaQueryWrapper<FileNode>().eq(FileNode::getUserId, userId));
            userMapper.deleteById(userId);
        }
        createdUserIds.clear();
        for (String spaceId : createdSpaceIds) {
            storageSpaceMapper.deleteById(spaceId);
        }
        createdSpaceIds.clear();
    }

    // ---------- 页面写操作集成测试 ----------

    @Test
    void shouldPublishCreateOnUpload() throws Exception {
        UserVo user = prepareUser();
        FileNodeVo file = fileService.upload(buildFile("hello.txt", "Hello"), user.getId(),
                FileNodeConstants.ROOT_ID, null);

        assertEquals(1, capturingListener.events().size());
        FileTreeChangedEvent event = capturingListener.events().get(0);
        assertEquals(FileChangeOperation.CREATE, event.getOperation());
        assertEquals(user.getId(), event.getUserId());
        assertEquals(file.getId(), event.getNodeId());
        assertEquals(FileNodeConstants.TYPE_FILE, event.getNodeType());
        assertEquals("hello.txt", event.getName());
        assertEquals(5L, event.getSize());
        assertNull(event.getOldParentId());
        assertEquals(FileNodeConstants.ROOT_ID, event.getNewParentId());
        assertNull(event.getOldPath());
        assertEquals(FileNodeConstants.ROOT_ID, event.getNewPath());
    }

    @Test
    void shouldPublishCreateOnCreateFolder() {
        UserVo user = prepareUser();
        FileNodeVo folder = createFolder(user.getId(), "docs", FileNodeConstants.ROOT_ID);

        assertEquals(1, capturingListener.events().size());
        FileTreeChangedEvent event = capturingListener.events().get(0);
        assertEquals(FileChangeOperation.CREATE, event.getOperation());
        assertEquals(folder.getId(), event.getNodeId());
        assertEquals(FileNodeConstants.TYPE_FOLDER, event.getNodeType());
        assertEquals("docs", event.getName());
        assertEquals(0L, event.getSize());
        assertEquals(FileNodeConstants.ROOT_ID, event.getNewParentId());
        assertEquals(FileNodeConstants.ROOT_ID, event.getNewPath());
    }

    @Test
    void shouldPublishUpdateOnRename() throws Exception {
        UserVo user = prepareUser();
        FileNodeVo file = fileService.upload(buildFile("old.txt", "Hi"), user.getId(),
                FileNodeConstants.ROOT_ID, null);

        FileRenameDto dto = new FileRenameDto();
        dto.setId(file.getId());
        dto.setNewName("new.txt");
        fileOperationService.rename(dto, user.getId());

        List<FileTreeChangedEvent> events = capturingListener.events();
        assertEquals(2, events.size());
        FileTreeChangedEvent event = events.get(1);
        assertEquals(FileChangeOperation.UPDATE, event.getOperation());
        assertEquals(file.getId(), event.getNodeId());
        assertEquals("new.txt", event.getName());
        assertEquals(FileNodeConstants.ROOT_ID, event.getOldParentId());
        assertEquals(FileNodeConstants.ROOT_ID, event.getNewParentId());
        assertEquals(FileNodeConstants.ROOT_ID, event.getOldPath());
        assertEquals(FileNodeConstants.ROOT_ID, event.getNewPath());
    }

    @Test
    void shouldPublishMoveOnMove() throws Exception {
        UserVo user = prepareUser();
        FileNodeVo folder = createFolder(user.getId(), "docs", FileNodeConstants.ROOT_ID);
        FileNodeVo file = fileService.upload(buildFile("a.txt", "A"), user.getId(),
                FileNodeConstants.ROOT_ID, null);

        FileExecuteOperationDto dto = buildOperationDto("move", folder.getId(), file);
        fileOperationService.move(dto, user.getId());

        FileTreeChangedEvent event = lastEvent();
        assertEquals(FileChangeOperation.MOVE, event.getOperation());
        assertEquals(file.getId(), event.getNodeId());
        assertEquals(FileNodeConstants.ROOT_ID, event.getOldParentId());
        assertEquals(folder.getId(), event.getNewParentId());
        assertEquals(FileNodeConstants.ROOT_ID, event.getOldPath());
        assertEquals(FileNodeConstants.ROOT_ID + FileNodeConstants.PATH_SEPARATOR + folder.getId(),
                event.getNewPath());
    }

    @Test
    void shouldPublishCopyOnCopy() throws Exception {
        UserVo user = prepareUser();
        FileNodeVo folder = createFolder(user.getId(), "backup", FileNodeConstants.ROOT_ID);
        FileNodeVo file = fileService.upload(buildFile("a.txt", "A"), user.getId(),
                FileNodeConstants.ROOT_ID, null);

        FileExecuteOperationDto dto = buildOperationDto("copy", folder.getId(), file);
        List<OperationResultVo> results = fileOperationService.copy(dto, user.getId());

        FileTreeChangedEvent event = lastEvent();
        assertEquals(FileChangeOperation.COPY, event.getOperation());
        assertEquals(results.get(0).getNodeId(), event.getNodeId());
        assertEquals(FileNodeConstants.ROOT_ID, event.getOldParentId());
        assertEquals(folder.getId(), event.getNewParentId());
    }

    @Test
    void shouldPublishDeleteOnTrash() throws Exception {
        UserVo user = prepareUser();
        FileNodeVo file = fileService.upload(buildFile("a.txt", "A"), user.getId(),
                FileNodeConstants.ROOT_ID, null);

        FileDeleteDto dto = new FileDeleteDto();
        dto.setIds(List.of(file.getId()));
        fileRecycleService.deleteToTrash(dto, user.getId());

        FileTreeChangedEvent event = lastEvent();
        assertEquals(FileChangeOperation.DELETE, event.getOperation());
        assertEquals(file.getId(), event.getNodeId());
        assertEquals(FileNodeConstants.ROOT_ID, event.getOldParentId());
        assertNull(event.getNewParentId());
        assertEquals(FileNodeConstants.ROOT_ID, event.getOldPath());
        assertNull(event.getNewPath());
    }

    @Test
    void shouldPublishRestoreOnRestore() throws Exception {
        UserVo user = prepareUser();
        FileNodeVo file = fileService.upload(buildFile("a.txt", "A"), user.getId(),
                FileNodeConstants.ROOT_ID, null);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(file.getId()));
        String recordId = fileRecycleService.deleteToTrash(deleteDto, user.getId()).get(0).getNodeId();

        FileExecuteRestoreDto restoreDto = new FileExecuteRestoreDto();
        RestoreItemDto item = new RestoreItemDto();
        item.setId(recordId);
        item.setStrategy(ConflictStrategy.OVERWRITE.getCode());
        restoreDto.setItems(List.of(item));
        fileRecycleService.restore(restoreDto, user.getId());

        FileTreeChangedEvent event = lastEvent();
        assertEquals(FileChangeOperation.RESTORE, event.getOperation());
        assertEquals("a.txt", event.getName());
        assertEquals(FileNodeConstants.ROOT_ID, event.getNewParentId());
        assertEquals(FileNodeConstants.ROOT_ID, event.getNewPath());
    }

    @Test
    void shouldPublishPermanentDeleteOnPermanentDelete() throws Exception {
        UserVo user = prepareUser();
        FileNodeVo file = fileService.upload(buildFile("a.txt", "A"), user.getId(),
                FileNodeConstants.ROOT_ID, null);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(file.getId()));
        String recordId = fileRecycleService.deleteToTrash(deleteDto, user.getId()).get(0).getNodeId();

        FilePermanentDeleteDto dto = new FilePermanentDeleteDto();
        dto.setIds(List.of(recordId));
        fileRecycleService.permanentDelete(dto, user.getId());

        FileTreeChangedEvent event = lastEvent();
        assertEquals(FileChangeOperation.PERMANENT_DELETE, event.getOperation());
        assertEquals(recordId, event.getNodeId());
        assertEquals("a.txt", event.getName());
        assertNull(event.getOldParentId());
        assertNull(event.getNewParentId());
        assertNull(event.getOldPath());
        assertNull(event.getNewPath());
    }

    @Test
    void shouldNotPublishWhenTransactionRollsBack() {
        new TransactionTemplate(transactionManager).execute(status -> {
            fileChangeEventSupport.publishAfterCommit(new FileTreeChangedEvent(this,
                    FileChangeOperation.CREATE, "u-rollback", "n1", FileNodeConstants.TYPE_FILE,
                    "a.txt", 1L, null, FileNodeConstants.ROOT_ID, null, FileNodeConstants.ROOT_ID));
            status.setRollbackOnly();
            return null;
        });

        assertTrue(capturingListener.events().isEmpty());
    }

    // ---------- WebDAV 埋点 Mockito 单测 ----------

    @Test
    void webDavUploadPublishesCreateEvent() throws Exception {
        WebDavMocks mocks = webDavMocks();
        User user = buildUser();
        StorageSpace space = buildSpace();
        when(mocks.userSpaceSupport().requireUser(TEST_USER_ID)).thenReturn(user);
        when(mocks.userSpaceSupport().requireSpace(TEST_SPACE_ID)).thenReturn(space);
        when(mocks.fileMapper().selectList(any())).thenReturn(List.of());
        HttpServletRequest request = mock(HttpServletRequest.class);
        ByteArrayInputStream content = new ByteArrayInputStream("hello".getBytes());
        when(request.getInputStream()).thenReturn(new jakarta.servlet.ServletInputStream() {
            @Override
            public boolean isFinished() {
                return content.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(jakarta.servlet.ReadListener readListener) {
            }

            @Override
            public int read() {
                return content.read();
            }
        });

        mocks.helper().upload(TEST_USER_ID, buildRootFolder(), "a.txt", request, 5);

        verify(mocks.eventSupport()).publishAfterCommit(argThat(e ->
                FileChangeOperation.CREATE.equals(e.getOperation())
                        && TEST_USER_ID.equals(e.getUserId())
                        && FileNodeConstants.ROOT_ID.equals(e.getNewParentId())));
    }

    @Test
    void webDavCreateFolderPublishesCreateEvent() {
        WebDavMocks mocks = webDavMocks();
        when(mocks.userMapper().selectById(TEST_USER_ID)).thenReturn(buildUser());

        mocks.helper().createFolder(TEST_USER_ID, FileNodeConstants.ROOT_ID, "docs");

        verify(mocks.eventSupport()).publishAfterCommit(argThat(e ->
                FileChangeOperation.CREATE.equals(e.getOperation())
                        && FileNodeConstants.TYPE_FOLDER.equals(e.getNodeType())
                        && FileNodeConstants.ROOT_ID.equals(e.getNewParentId())));
    }

    @Test
    void webDavDeletePublishesDeleteEvent() throws Exception {
        WebDavMocks mocks = webDavMocks();
        when(mocks.userSpaceSupport().requireUser(TEST_USER_ID)).thenReturn(buildUser());
        when(mocks.storageSpaceMapper().selectById(TEST_SPACE_ID)).thenReturn(buildSpace());
        Path realFile = tempDir.resolve("files").resolve(TEST_USERNAME).resolve("a.txt");
        Files.createDirectories(realFile.getParent());
        Files.writeString(realFile, "hello");
        FileNode file = buildFileNode("f1", FileNodeConstants.ROOT_ID, "a.txt", 5);

        mocks.helper().delete(TEST_USER_ID, file);

        verify(mocks.eventSupport()).publishAfterCommit(argThat(e ->
                FileChangeOperation.DELETE.equals(e.getOperation())
                        && "f1".equals(e.getNodeId())
                        && FileNodeConstants.ROOT_ID.equals(e.getOldParentId())));
    }

    @Test
    void webDavMovePublishesMoveEvent() throws Exception {
        WebDavMocks mocks = webDavMocks();
        when(mocks.userSpaceSupport().requireSpace(TEST_SPACE_ID)).thenReturn(buildSpace());
        when(mocks.userSpaceSupport().requireUser(TEST_USER_ID)).thenReturn(buildUser());
        FileNode source = buildFileNode("f1", FileNodeConstants.ROOT_ID, "a.txt", 5);
        FileNode targetParent = buildFolderNode("p1", FileNodeConstants.ROOT_ID, "docs");
        Path sourceFile = tempDir.resolve("files").resolve(TEST_USERNAME).resolve("a.txt");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "hello");

        mocks.helper().move(TEST_USER_ID, source, targetParent, "a.txt");

        verify(mocks.eventSupport()).publishAfterCommit(argThat(e ->
                FileChangeOperation.MOVE.equals(e.getOperation())
                        && "f1".equals(e.getNodeId())
                        && FileNodeConstants.ROOT_ID.equals(e.getOldParentId())
                        && "p1".equals(e.getNewParentId())));
    }

    @Test
    void webDavCopyPublishesCopyEvent() throws Exception {
        WebDavMocks mocks = webDavMocks();
        when(mocks.userSpaceSupport().requireUser(TEST_USER_ID)).thenReturn(buildUser());
        when(mocks.userSpaceSupport().requireSpace(TEST_SPACE_ID)).thenReturn(buildSpace());
        FileNode source = buildFileNode("f1", FileNodeConstants.ROOT_ID, "a.txt", 5);
        source.setHash("h1");
        FileNode targetParent = buildFolderNode("p1", FileNodeConstants.ROOT_ID, "docs");
        Path sourceFile = tempDir.resolve("files").resolve(TEST_USERNAME).resolve("a.txt");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "hello");

        mocks.helper().copy(TEST_USER_ID, source, targetParent, "a.txt");

        verify(mocks.eventSupport()).publishAfterCommit(argThat(e ->
                FileChangeOperation.COPY.equals(e.getOperation())
                        && "p1".equals(e.getNewParentId())));
    }

    // ---------- 传输埋点 Mockito 单测 ----------

    @Test
    void transferToLocalPublishesCreateEvent() throws Exception {
        TransferMocks mocks = transferMocks();
        TransferTask task = buildTransferTask("copy", "local", null, "local", null);
        TransferContext ctx = new TransferContext(task, mocks.transferTaskMapper(), new ObjectMapper());
        when(mocks.fileMapper().selectById("f1")).thenReturn(buildFileNode("f1", FileNodeConstants.ROOT_ID, "a.txt", 5));
        when(mocks.fileMapper().selectList(any())).thenReturn(List.of());
        User user = buildUser();
        when(mocks.userSpaceSupport().requireUser(TEST_USER_ID)).thenReturn(user);
        when(mocks.userSpaceSupport().requireSpace(user)).thenReturn(buildSpace());
        Path sourceFile = tempDir.resolve("files").resolve(TEST_USERNAME).resolve("a.txt");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "hello");

        mocks.support().transferTopLevel(buildTransferItem("f1"), task, ctx);

        verify(mocks.eventSupport()).publishAfterCommit(argThat(e ->
                FileChangeOperation.CREATE.equals(e.getOperation())
                        && TEST_USER_ID.equals(e.getUserId())
                        && FileNodeConstants.ROOT_ID.equals(e.getNewParentId())));
    }

    @Test
    void transferCreateTargetFolderPublishesCreateEvent() {
        TransferMocks mocks = transferMocks();
        TransferTask task = buildTransferTask("copy", "local", null, "local", null);
        TransferContext ctx = new TransferContext(task, mocks.transferTaskMapper(), new ObjectMapper());
        FileNode sourceFolder = buildFolderNode("d1", FileNodeConstants.ROOT_ID, "docs");
        when(mocks.fileMapper().selectById("d1")).thenReturn(sourceFolder);
        when(mocks.fileMapper().selectList(any())).thenReturn(List.of());
        when(mocks.fileMapper().selectByParentId(TEST_USER_ID, "d1")).thenReturn(List.of());
        when(mocks.userMapper().selectById(TEST_USER_ID)).thenReturn(buildUser());

        mocks.support().transferTopLevel(buildTransferItem("d1"), task, ctx);

        verify(mocks.eventSupport()).publishAfterCommit(argThat(e ->
                FileChangeOperation.CREATE.equals(e.getOperation())
                        && FileNodeConstants.TYPE_FOLDER.equals(e.getNodeType())
                        && FileNodeConstants.ROOT_ID.equals(e.getNewParentId())));
    }

    @Test
    void transferMoveModePublishesCreateAndDeleteEvents() throws Exception {
        TransferMocks mocks = transferMocks();
        TransferTask task = buildTransferTask("move", "remote", "m1", "local", null);
        TransferContext ctx = new TransferContext(task, mocks.transferTaskMapper(), new ObjectMapper());
        FileNode source = buildFileNode("f1", FileNodeConstants.ROOT_ID, "a.txt", 5);
        source.setSourceType(FileNodeConstants.SOURCE_REMOTE);
        source.setRemoteMountId("m1");
        when(mocks.fileMapper().selectById("f1")).thenReturn(source);
        when(mocks.fileMapper().selectList(any())).thenReturn(List.of());
        User user = buildUser();
        when(mocks.userSpaceSupport().requireUser(TEST_USER_ID)).thenReturn(user);
        when(mocks.userSpaceSupport().requireSpace(user)).thenReturn(buildSpace());
        RLock lock = mock(RLock.class);
        when(mocks.remoteMountLock().getLock("m1")).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        RemoteProtocolAdapter adapter = mock(RemoteProtocolAdapter.class);
        when(mocks.adapterFactory().create(any())).thenReturn(adapter);
        when(adapter.download(any())).thenReturn(new ByteArrayInputStream("hello".getBytes()));
        when(mocks.remoteMountSupport().requireOwnedMount("m1", TEST_USER_ID))
                .thenReturn(buildRemoteMount("m1"));

        mocks.support().transferTopLevel(buildTransferItem("f1"), task, ctx);

        verify(mocks.eventSupport()).publishAfterCommit(argThat(e ->
                FileChangeOperation.CREATE.equals(e.getOperation())
                        && TEST_USER_ID.equals(e.getUserId())));
        verify(mocks.eventSupport()).publishAfterCommit(argThat(e ->
                FileChangeOperation.DELETE.equals(e.getOperation())
                        && "f1".equals(e.getNodeId())
                        && FileNodeConstants.ROOT_ID.equals(e.getOldParentId())));
        verify(mocks.remoteFileOperationService()).delete(source, TEST_USER_ID);
    }

    // ---------- 工具方法 ----------

    private UserVo prepareUser() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        createdUserIds.add(userWithSpace.user().getId());
        createdSpaceIds.add(userWithSpace.space().getId());
        return userWithSpace.user();
    }

    private FileNodeVo createFolder(String userId, String name, String parentId) {
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(parentId);
        dto.setName(name);
        return fileOperationService.createFolder(dto, userId);
    }

    private FileExecuteOperationDto buildOperationDto(String type, String targetParentId, FileNodeVo file) {
        FileExecuteOperationDto dto = new FileExecuteOperationDto();
        dto.setType(type);
        dto.setTargetParentId(targetParentId);
        OperationItemDto item = new OperationItemDto();
        item.setId(file.getId());
        item.setName(file.getName());
        item.setStrategy(ConflictStrategy.OVERWRITE.getCode());
        dto.setItems(List.of(item));
        return dto;
    }

    private MultipartFile buildFile(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes());
    }

    private FileTreeChangedEvent lastEvent() {
        List<FileTreeChangedEvent> events = capturingListener.events();
        return events.get(events.size() - 1);
    }

    private WebDavMocks webDavMocks() {
        FileMapper fileMapper = mock(FileMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        StorageSpaceMapper storageSpaceMapper = mock(StorageSpaceMapper.class);
        UserSpaceSupport userSpaceSupport = mock(UserSpaceSupport.class);
        FileChangeEventSupport eventSupport = mock(FileChangeEventSupport.class);
        FileNodeSupport fileNodeSupport = new FileNodeSupport(fileMapper, userMapper);
        FilePathSupport filePathSupport = new FilePathSupport(fileMapper);
        WebDavFileOperationHelper helper = new WebDavFileOperationHelper(fileMapper, userMapper,
                storageSpaceMapper, userSpaceSupport, fileNodeSupport, filePathSupport, eventSupport);
        return new WebDavMocks(helper, eventSupport, fileMapper, userMapper, userSpaceSupport,
                storageSpaceMapper);
    }

    private TransferMocks transferMocks() {
        FileMapper fileMapper = mock(FileMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        UserSpaceSupport userSpaceSupport = mock(UserSpaceSupport.class);
        RemoteMountSupport remoteMountSupport = mock(RemoteMountSupport.class);
        RemoteProtocolAdapterFactory adapterFactory = mock(RemoteProtocolAdapterFactory.class);
        RemoteMountLock remoteMountLock = mock(RemoteMountLock.class);
        RemoteFileOperationService remoteFileOperationService = mock(RemoteFileOperationService.class);
        TransferTaskMapper transferTaskMapper = mock(TransferTaskMapper.class);
        FileChangeEventSupport eventSupport = mock(FileChangeEventSupport.class);
        FileNodeSupport fileNodeSupport = new FileNodeSupport(fileMapper, userMapper);
        FilePathSupport filePathSupport = new FilePathSupport(fileMapper);
        TransferNodeSupport support = new TransferNodeSupport(fileMapper, fileNodeSupport, filePathSupport,
                userSpaceSupport, remoteMountSupport, adapterFactory, remoteMountLock,
                remoteFileOperationService, mock(WebDavFileOperationHelper.class),
                mock(TrashDeleteSupport.class), eventSupport);
        return new TransferMocks(support, eventSupport, fileMapper, userMapper, userSpaceSupport,
                remoteMountSupport, adapterFactory, remoteMountLock, remoteFileOperationService,
                transferTaskMapper);
    }

    private User buildUser() {
        User user = new User();
        user.setId(TEST_USER_ID);
        user.setUsername(TEST_USERNAME);
        user.setStorageSpaceId(TEST_SPACE_ID);
        user.setUsedSpace(0L);
        user.setQuota(0L);
        return user;
    }

    private StorageSpace buildSpace() {
        StorageSpace space = new StorageSpace();
        space.setId(TEST_SPACE_ID);
        space.setPath(tempDir.toString());
        return space;
    }

    private FileNode buildRootFolder() {
        return buildFolderNode(FileNodeConstants.ROOT_ID, FileNodeConstants.ROOT_ID, "");
    }

    private FileNode buildFolderNode(String id, String path, String name) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setUserId(TEST_USER_ID);
        node.setParentId(FileNodeConstants.ROOT_ID);
        node.setName(name);
        node.setType(FileNodeConstants.TYPE_FOLDER);
        node.setPath(path);
        node.setSize(0L);
        node.setStorageSpaceId(TEST_SPACE_ID);
        return node;
    }

    private FileNode buildFileNode(String id, String parentId, String name, long size) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setUserId(TEST_USER_ID);
        node.setParentId(parentId);
        node.setName(name);
        node.setType(FileNodeConstants.TYPE_FILE);
        node.setPath(FileNodeConstants.ROOT_ID);
        node.setSize(size);
        node.setStorageSpaceId(TEST_SPACE_ID);
        node.setSourceType(FileNodeConstants.SOURCE_LOCAL);
        node.setStatus(1);
        return node;
    }

    private TransferTask buildTransferTask(String opType, String sourceType, String sourceMountId,
                                           String targetType, String targetMountId) {
        TransferTask task = new TransferTask();
        task.setId("t1");
        task.setUserId(TEST_USER_ID);
        task.setOpType(opType);
        task.setSourceType(sourceType);
        task.setSourceMountId(sourceMountId);
        task.setTargetType(targetType);
        task.setTargetMountId(targetMountId);
        task.setTargetParentId(FileNodeConstants.ROOT_ID);
        return task;
    }

    private TransferItem buildTransferItem(String nodeId) {
        TransferItem item = new TransferItem();
        item.setNodeId(nodeId);
        item.setName("a.txt");
        item.setType(FileNodeConstants.TYPE_FILE);
        item.setSize(5L);
        item.setFinalName("a.txt");
        item.setStrategy(ConflictStrategy.KEEP.getCode());
        return item;
    }

    private com.fleyx.jcloud.model.po.RemoteMount buildRemoteMount(String id) {
        com.fleyx.jcloud.model.po.RemoteMount mount = new com.fleyx.jcloud.model.po.RemoteMount();
        mount.setId(id);
        mount.setUserId(TEST_USER_ID);
        return mount;
    }

    private record WebDavMocks(WebDavFileOperationHelper helper, FileChangeEventSupport eventSupport,
                               FileMapper fileMapper, UserMapper userMapper,
                               UserSpaceSupport userSpaceSupport, StorageSpaceMapper storageSpaceMapper) {
    }

    private record TransferMocks(TransferNodeSupport support, FileChangeEventSupport eventSupport,
                                 FileMapper fileMapper, UserMapper userMapper,
                                 UserSpaceSupport userSpaceSupport,
                                 RemoteMountSupport remoteMountSupport,
                                 RemoteProtocolAdapterFactory adapterFactory,
                                 RemoteMountLock remoteMountLock,
                                 RemoteFileOperationService remoteFileOperationService,
                                 TransferTaskMapper transferTaskMapper) {
    }

    private static class CapturingListener implements ApplicationListener<FileTreeChangedEvent> {

        private final List<FileTreeChangedEvent> events = new ArrayList<>();

        @Override
        public void onApplicationEvent(FileTreeChangedEvent event) {
            events.add(event);
        }

        List<FileTreeChangedEvent> events() {
            return events;
        }
    }
}
