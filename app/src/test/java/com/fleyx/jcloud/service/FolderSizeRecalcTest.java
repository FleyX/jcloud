package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.FileChangeOperation;
import com.fleyx.jcloud.common.event.FileTreeChangedEvent;
import com.fleyx.jcloud.common.event.SyncCompletedEvent;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.RecycleRecordMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileDeleteDto;
import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.dto.FileExecuteRestoreDto;
import com.fleyx.jcloud.model.dto.FilePageQueryDto;
import com.fleyx.jcloud.model.dto.OperationItemDto;
import com.fleyx.jcloud.model.dto.RestoreItemDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.support.FolderSizeRecalcSupport;
import com.fleyx.jcloud.util.FilePathUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 文件夹大小持久化统计测试（票据 03）。
 * <p>
 * 监听器为异步执行且独立读库，本类不使用事务回滚（异步线程看不到未提交数据），
 * 测试数据按唯一用户隔离并在用例结束后物理清理。
 * 防抖窗口经反射缩短为 200ms（不用 @TestPropertySource，避免产生独立 Spring 上下文），
 * 断言为异步生效的 DB 值，使用轮询工具方法（100ms 间隔、5s 超时）。
 */
class FolderSizeRecalcTest extends IntegrationTestBase {

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
    private FolderSizeRecalcSupport folderSizeRecalcSupport;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private final List<String> createdUserIds = new ArrayList<>();
    private final List<String> createdSpaceIds = new ArrayList<>();

    /**
     * 反射缩短防抖窗口为 200ms：避免 @TestPropertySource 产生独立 Spring 上下文，
     * 同时把每个用例的防抖等待从秒级压到亚秒级。
     */
    @BeforeEach
    void shrinkDebounceWindow() {
        ReflectionTestUtils.setField(folderSizeRecalcSupport, "debounceWindowMs", 200L);
    }

    @AfterEach
    void cleanup() {
        for (String userId : createdUserIds) {
            recycleRecordMapper.delete(new LambdaQueryWrapper<RecycleRecord>().eq(RecycleRecord::getUserId, userId));
            fileMapper.delete(new LambdaQueryWrapper<FileNode>().eq(FileNode::getUserId, userId));
            userMapper.deleteById(userId);
        }
        createdUserIds.clear();
        createdSpaceIds.forEach(storageSpaceMapper::deleteById);
        createdSpaceIds.clear();
    }

    /**
     * a. 两层嵌套文件夹 + 上传文件：防抖后各级祖先 size 逐层正确（含根下第一层）。
     */
    @Test
    void shouldRecalcAncestorSizesAfterUpload() throws Exception {
        UserVo user = prepareUser();
        FileNodeVo level1 = createFolder(user.getId(), "level1", FileNodeConstants.ROOT_ID);
        FileNodeVo level2 = createFolder(user.getId(), "level2", level1.getId());
        uploadFile(user.getId(), level2.getId(), "a.txt", "hello");

        pollFolderSize(level2.getId(), 5L);
        pollFolderSize(level1.getId(), 5L);
    }

    /**
     * b. 删除进回收站：祖先链 size 扣除。
     */
    @Test
    void shouldDeductSizesAfterTrashDelete() throws Exception {
        UserVo user = prepareUser();
        FileNodeVo level1 = createFolder(user.getId(), "level1", FileNodeConstants.ROOT_ID);
        FileNodeVo level2 = createFolder(user.getId(), "level2", level1.getId());
        uploadFile(user.getId(), level2.getId(), "a.txt", "hello");
        pollFolderSize(level2.getId(), 5L);
        pollFolderSize(level1.getId(), 5L);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(fileMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getUserId, user.getId())
                .eq(FileNode::getType, FileNodeConstants.TYPE_FILE)).getId()));
        fileRecycleService.deleteToTrash(deleteDto, user.getId());

        pollFolderSize(level2.getId(), 0L);
        pollFolderSize(level1.getId(), 0L);
    }

    /**
     * c. 恢复：删除整个文件夹进回收站（含文件）再恢复，文件夹自身 size 与其祖先均重新计入。
     */
    @Test
    void shouldRecountSizesAfterRestore() throws Exception {
        UserVo user = prepareUser();
        FileNodeVo level1 = createFolder(user.getId(), "level1", FileNodeConstants.ROOT_ID);
        FileNodeVo level2 = createFolder(user.getId(), "level2", level1.getId());
        uploadFile(user.getId(), level2.getId(), "a.txt", "hello");
        pollFolderSize(level2.getId(), 5L);
        pollFolderSize(level1.getId(), 5L);

        FileDeleteDto folderDeleteDto = new FileDeleteDto();
        folderDeleteDto.setIds(List.of(level2.getId()));
        String folderRecordId = fileRecycleService.deleteToTrash(folderDeleteDto, user.getId()).get(0).getNodeId();
        pollFolderSize(level1.getId(), 0L);

        String restoredFolderId = restore(user.getId(), folderRecordId, ConflictStrategy.OVERWRITE.getCode());
        pollFolderSize(restoredFolderId, 5L);
        pollFolderSize(level1.getId(), 5L);
    }

    /**
     * d. 移动文件夹到另一文件夹：新旧双侧祖先链均正确。
     */
    @Test
    void shouldRecalcBothSidesAfterMove() throws Exception {
        UserVo user = prepareUser();
        FileNodeVo src = createFolder(user.getId(), "src", FileNodeConstants.ROOT_ID);
        FileNodeVo dst = createFolder(user.getId(), "dst", FileNodeConstants.ROOT_ID);
        FileNodeVo sub = createFolder(user.getId(), "sub", src.getId());
        uploadFile(user.getId(), sub.getId(), "a.txt", "hello");
        pollFolderSize(src.getId(), 5L);
        pollFolderSize(sub.getId(), 5L);
        assertEquals(0L, sizeOf(dst.getId()));

        FileExecuteOperationDto dto = new FileExecuteOperationDto();
        dto.setType("move");
        dto.setTargetParentId(dst.getId());
        OperationItemDto item = new OperationItemDto();
        item.setId(sub.getId());
        item.setName("sub");
        item.setStrategy(ConflictStrategy.OVERWRITE.getCode());
        dto.setItems(List.of(item));
        fileOperationService.move(dto, user.getId());

        pollFolderSize(src.getId(), 0L);
        pollFolderSize(dst.getId(), 5L);
        pollFolderSize(sub.getId(), 5L);
    }

    /**
     * e. 远程文件节点计入文件夹大小。
     */
    @Test
    void shouldCountRemoteFileInFolderSize() throws Exception {
        UserVo user = prepareUser();
        FileNodeVo folder = createFolder(user.getId(), "folder", FileNodeConstants.ROOT_ID);
        pollFolderSize(folder.getId(), 0L);
        FileNode folderNode = fileMapper.selectById(folder.getId());

        FileNode remote = new FileNode();
        remote.setUserId(user.getId());
        remote.setParentId(folder.getId());
        remote.setName("remote.bin");
        remote.setType(FileNodeConstants.TYPE_FILE);
        remote.setSize(100L);
        remote.setSourceType(FileNodeConstants.SOURCE_REMOTE);
        remote.setPath(FilePathUtil.fullIdPath(folderNode));
        fileMapper.insert(remote);

        eventPublisher.publishEvent(new FileTreeChangedEvent(this, FileChangeOperation.CREATE, user.getId(),
                remote.getId(), remote.getType(), remote.getName(), remote.getSize(),
                null, folder.getId(), null, FilePathUtil.fullIdPath(folderNode)));

        pollFolderSize(folder.getId(), 100L);
    }

    /**
     * f. 启动全量对账兜底：手动改错 size 后全量重算纠正。
     */
    @Test
    void shouldFixSizesOnFullReconcile() throws Exception {
        UserVo user = prepareUser();
        FileNodeVo folder = createFolder(user.getId(), "folder", FileNodeConstants.ROOT_ID);
        uploadFile(user.getId(), folder.getId(), "a.txt", "hello");
        pollFolderSize(folder.getId(), 5L);

        corruptSize(folder.getId(), 0L);
        assertEquals(0L, sizeOf(folder.getId()));

        folderSizeRecalcSupport.reconcileUser(user.getId());
        assertEquals(5L, sizeOf(folder.getId()));
    }

    /**
     * g. 同步完成事件兜底：手动改错 size 后 TYPE_USER 同步事件触发全树重算。
     */
    @Test
    void shouldFixSizesOnUserSyncCompleted() throws Exception {
        UserVo user = prepareUser();
        FileNodeVo folder = createFolder(user.getId(), "folder", FileNodeConstants.ROOT_ID);
        uploadFile(user.getId(), folder.getId(), "a.txt", "hello");
        pollFolderSize(folder.getId(), 5L);

        corruptSize(folder.getId(), 0L);
        eventPublisher.publishEvent(new SyncCompletedEvent(this, user.getId(), SyncCompletedEvent.TYPE_USER));

        pollFolderSize(folder.getId(), 5L);
    }

    /**
     * h. 文件列表接口对文件夹返回持久化 size（票据 04）。
     */
    @Test
    void shouldReturnFolderSizeFromListApi() throws Exception {
        UserVo user = prepareUser();
        FileNodeVo folder = createFolder(user.getId(), "folder", FileNodeConstants.ROOT_ID);
        uploadFile(user.getId(), folder.getId(), "a.txt", "hello");
        pollFolderSize(folder.getId(), 5L);

        FilePageQueryDto query = new FilePageQueryDto();
        query.setParentId(FileNodeConstants.ROOT_ID);
        FileNodeVo folderVo = fileService.list(query, user.getId()).getRecords().stream()
                .filter(node -> FileNodeConstants.TYPE_FOLDER.equals(node.getType()) && "folder".equals(node.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("列表接口未返回文件夹节点"));

        assertEquals("5", folderVo.getSize());
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

    private FileNodeVo uploadFile(String userId, String parentId, String name, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", name, "text/plain", content.getBytes());
        return fileService.upload(file, userId, parentId, null);
    }

    private String restore(String userId, String recordId, String strategy) {
        FileExecuteRestoreDto dto = new FileExecuteRestoreDto();
        RestoreItemDto item = new RestoreItemDto();
        item.setId(recordId);
        item.setStrategy(strategy);
        dto.setItems(List.of(item));
        return fileRecycleService.restore(dto, userId).get(0).getNodeId();
    }

    private void corruptSize(String folderId, long size) {
        FileNode corrupt = new FileNode();
        corrupt.setId(folderId);
        corrupt.setSize(size);
        fileMapper.updateById(corrupt);
    }

    private long sizeOf(String nodeId) {
        FileNode node = fileMapper.selectById(nodeId);
        return node == null || node.getSize() == null ? 0L : node.getSize();
    }

    /**
     * 轮询文件夹 size 至期望值：100ms 间隔，5s 超时。
     */
    private void pollFolderSize(String folderId, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        long actual = sizeOf(folderId);
        while (actual != expected) {
            if (System.currentTimeMillis() > deadline) {
                fail("文件夹大小未收敛: folderId=" + folderId + ", 期望=" + expected + ", 实际=" + actual);
            }
            Thread.sleep(100);
            actual = sizeOf(folderId);
        }
    }
}
