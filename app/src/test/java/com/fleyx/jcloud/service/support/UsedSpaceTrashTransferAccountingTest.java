package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.TransferTaskStatus;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.RemoteMountMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.TransferTaskMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.bo.TransferItem;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileDeleteDto;
import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.dto.FileExecuteRestoreDto;
import com.fleyx.jcloud.model.dto.FilePermanentDeleteDto;
import com.fleyx.jcloud.model.dto.OperationItemDto;
import com.fleyx.jcloud.model.dto.RestoreItemDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.po.TransferTask;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.OperationResultVo;
import com.fleyx.jcloud.service.FileOperationService;
import com.fleyx.jcloud.service.FileRecycleService;
import com.fleyx.jcloud.service.FileService;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.service.RemoteProtocolAdapter;
import com.fleyx.jcloud.service.TransferService;
import com.fleyx.jcloud.service.impl.RemoteProtocolAdapterFactory;
import com.fleyx.jcloud.service.impl.TransferTaskExecutor;
import com.fleyx.jcloud.util.DiskSpaceUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.IdUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 03 票：回收站/媒体写回/跨来源传输集中记账 + 存储空间容量口径统一 集成测试。
 * <p>
 * 覆盖：删除→恢复循环 used_space 不漂移、恢复覆盖冲突、媒体写回新建/覆盖（含负增量）、
 * 存储空间容量校验改读磁盘真值、同一用户并发执行传输与上传不丢更新。
 * 除并发用例（需提交数据供其他线程可见，手工清理）外均依赖类级事务回滚。
 */
@SpringBootTest
@Transactional
class UsedSpaceTrashTransferAccountingTest extends IntegrationTestBase {

    @Autowired
    private FileService fileService;

    @Autowired
    private FileOperationService fileOperationService;

    @Autowired
    private FileRecycleService fileRecycleService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private TransferTaskExecutor transferTaskExecutor;

    @Autowired
    private MediaArtworkPersistSupport mediaArtworkPersistSupport;

    @Autowired
    private RemoteFileService remoteFileService;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StorageSpaceMapper storageSpaceMapper;

    @Autowired
    private TransferTaskMapper transferTaskMapper;

    @Autowired
    private RemoteMountMapper remoteMountMapper;

    @Autowired
    private UserUsedSpaceSupport userUsedSpaceSupport;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RemoteProtocolAdapterFactory adapterFactory;

    private RemoteProtocolAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = Mockito.mock(RemoteProtocolAdapter.class);
        when(adapterFactory.create(any())).thenReturn(adapter);
    }

    // ---------- 回归：删除→恢复循环 ----------

    @Test
    void shouldKeepUsedSpaceStableAcrossDeleteRestoreCycle() {
        UserWithSpace uw = prepareUserWithStorageSpace();
        String userId = uw.user().getId();
        byte[] content = "cycle-content".getBytes(StandardCharsets.UTF_8);
        fileService.upload(buildFile("cycle.txt", content), userId, FileNodeConstants.ROOT_ID, null);
        long expected = content.length;
        assertUsedEqualsRecalc(userId, expected);

        // 删除进回收站：配额仍占用，不扣减
        String recordId1 = deleteToTrash(findRootFileByName(userId, "cycle.txt").getId(), userId);
        assertUsedEqualsRecalc(userId, expected);

        // 恢复：不重复累加（本票修复点——旧实现会 +size 导致漂移）
        restore(recordId1, userId);
        assertUsedEqualsRecalc(userId, expected);

        // 再次删除 → 再次恢复
        String recordId2 = deleteToTrash(findRootFileByName(userId, "cycle.txt").getId(), userId);
        assertUsedEqualsRecalc(userId, expected);
        restore(recordId2, userId);
        assertUsedEqualsRecalc(userId, expected);

        // 彻底删除：正确释放配额
        String recordId3 = deleteToTrash(findRootFileByName(userId, "cycle.txt").getId(), userId);
        permanentDelete(recordId3, userId);
        assertUsedEqualsRecalc(userId, 0L);
    }

    @Test
    void shouldKeepUsedSpaceStableOnRestoreOverwriteConflict() {
        UserWithSpace uw = prepareUserWithStorageSpace();
        String userId = uw.user().getId();
        byte[] original = "abcde".getBytes(StandardCharsets.UTF_8);
        byte[] other = "xyz".getBytes(StandardCharsets.UTF_8);
        fileService.upload(buildFile("a.txt", original), userId, FileNodeConstants.ROOT_ID, null);
        long expected = original.length;
        String recordId = deleteToTrash(findRootFileByName(userId, "a.txt").getId(), userId);
        assertUsedEqualsRecalc(userId, expected);

        // 目标位置新建同名文件：回收站记录 + 在表文件各计一次
        fileService.upload(buildFile("a.txt", other), userId, FileNodeConstants.ROOT_ID, null);
        assertUsedEqualsRecalc(userId, (long) original.length + other.length);

        // OVERWRITE 恢复：覆盖处理器原子扣减在表同名文件（-other），恢复节点入表不再累加
        restore(recordId, userId, ConflictStrategy.OVERWRITE);
        assertUsedEqualsRecalc(userId, original.length);
    }

    // ---------- 媒体写回 ----------

    @Test
    void shouldAccountMediaArtworkWriteBackNewAndOverwrite() {
        UserWithSpace uw = prepareUserWithStorageSpace();
        String userId = uw.user().getId();
        FileNode folder = fileMapper.selectById(createFolder(userId, "movie", FileNodeConstants.ROOT_ID).getId());
        assertNotNull(folder);

        byte[] nfo1 = "<xml>v1</xml>".getBytes(StandardCharsets.UTF_8);
        mediaArtworkPersistSupport.writeNfoXml(folder, "movie.nfo", new String(nfo1, StandardCharsets.UTF_8));
        assertUsedEqualsRecalc(userId, nfo1.length);

        // 覆盖为更小内容：负增量
        byte[] nfo2 = "x".getBytes(StandardCharsets.UTF_8);
        mediaArtworkPersistSupport.writeNfoXml(folder, "movie.nfo", new String(nfo2, StandardCharsets.UTF_8));
        assertUsedEqualsRecalc(userId, nfo2.length);

        // 覆盖为更大内容
        byte[] nfo3 = "much-longer-content".getBytes(StandardCharsets.UTF_8);
        mediaArtworkPersistSupport.writeNfoXml(folder, "movie.nfo", new String(nfo3, StandardCharsets.UTF_8));
        assertUsedEqualsRecalc(userId, nfo3.length);
    }

    // ---------- 容量校验（磁盘真值） ----------

    @Test
    void shouldRejectTransferWhenDiskCapacityExceeded() {
        // 配额不限：跳过用户配额校验，命中存储空间磁盘容量校验
        UserWithSpace uw = prepareUserWithStorageSpace(0L);
        String userId = uw.user().getId();
        FileNode mount = createMountNode(userId);
        long diskTotal = DiskSpaceUtil.calculateSpace(uw.space().getPath())[0];
        FileNode huge = insertRemoteFileNode(mount, userId, "huge.bin", diskTotal + 1L);

        FileExecuteOperationDto dto = new FileExecuteOperationDto();
        OperationItemDto item = new OperationItemDto();
        item.setId(huge.getId());
        dto.setItems(List.of(item));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transferService.createTransfer(dto, "copy", userId));
        assertTrue(ex.getMessage().contains("存储空间容量不足"), ex.getMessage());
    }

    // ---------- 传输并发 ----------

    @Test
    void shouldKeepUsedSpaceConsistentUnderConcurrentTransferAndUpload() throws Exception {
        UserWithSpace uw = prepareUserWithStorageSpace();
        String userId = uw.user().getId();

        FileNode mount = createMountNode(userId);
        byte[] remoteContent = "hello".getBytes(StandardCharsets.UTF_8);
        when(adapter.download(anyString())).thenReturn(new ByteArrayInputStream(remoteContent));
        FileNode remote = insertRemoteFileNode(mount, userId, "remote.txt", remoteContent.length);

        // 直接落库传输任务（绕过 createTransfer，避免 AFTER_COMMIT 异步事件与显式执行竞态）
        TransferTask task = buildTransferTask(userId, remote, mount);
        transferTaskMapper.insert(task);

        // 提交前置数据，使并发线程可见（其余用例靠类级事务回滚）
        TestTransaction.flagForCommit();
        TestTransaction.end();
        try {
            ExecutorService pool = Executors.newFixedThreadPool(6);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> transferFuture = pool.submit(() -> {
                try {
                    start.await();
                    transferTaskExecutor.execute(task.getId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            byte[][] contents = {
                    "u0-data".getBytes(StandardCharsets.UTF_8),
                    "u1-data".getBytes(StandardCharsets.UTF_8),
                    "u2-data".getBytes(StandardCharsets.UTF_8),
                    "u3-data".getBytes(StandardCharsets.UTF_8),
                    "u4-data".getBytes(StandardCharsets.UTF_8)
            };
            List<Future<?>> uploadFutures = new ArrayList<>();
            for (int i = 0; i < contents.length; i++) {
                final int idx = i;
                final byte[] content = contents[i];
                uploadFutures.add(pool.submit(() -> {
                    try {
                        start.await();
                        fileService.upload(buildFile("u" + idx + ".txt", content), userId,
                                FileNodeConstants.ROOT_ID, null);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            transferFuture.get(60, TimeUnit.SECONDS);
            for (Future<?> f : uploadFutures) {
                f.get(60, TimeUnit.SECONDS);
            }
            pool.shutdown();

            TransferTask done = transferTaskMapper.selectById(task.getId());
            assertEquals(TransferTaskStatus.COMPLETED.getValue(), done.getStatus());
            assertEquals(1L, done.getSuccessCount());

            long expected = remoteContent.length;
            for (byte[] c : contents) {
                expected += c.length;
            }
            assertUsedEqualsRecalc(userId, expected);
        } finally {
            // 清理已提交数据：传输任务、挂载、文件节点、用户、存储空间
            transferTaskMapper.deleteById(task.getId());
            remoteMountMapper.deleteById(mount.getRemoteMountId());
            List<FileNode> nodes = fileMapper.selectList(new LambdaQueryWrapper<FileNode>()
                    .eq(FileNode::getUserId, userId));
            if (!nodes.isEmpty()) {
                fileMapper.physicalDeleteByIds(nodes.stream().map(FileNode::getId).toList());
            }
            userMapper.deleteById(userId);
            storageSpaceMapper.deleteById(uw.space().getId());
        }
    }

    // ---------- 工具方法 ----------

    private long usedSpaceOf(String userId) {
        User user = userMapper.selectById(userId);
        return user == null || user.getUsedSpace() == null ? 0L : user.getUsedSpace();
    }

    /**
     * 断言记账值等于重算值与预期增量。先读记账值再重算，避免重算覆盖掩盖记账错误。
     */
    private void assertUsedEqualsRecalc(String userId, long expected) {
        long used = usedSpaceOf(userId);
        long recalc = userUsedSpaceSupport.recalcUsedSpace(userId);
        assertEquals(expected, used, "used_space 记账值");
        assertEquals(expected, recalc, "重算值");
    }

    private MockMultipartFile buildFile(String name, byte[] content) {
        return new MockMultipartFile("file", name, "text/plain", content);
    }

    private FileNodeVo createFolder(String userId, String name, String parentId) {
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(parentId);
        dto.setName(name);
        return fileOperationService.createFolder(dto, userId);
    }

    private FileNode findRootFileByName(String userId, String name) {
        return fileMapper.selectByParentId(userId, FileNodeConstants.ROOT_ID).stream()
                .filter(n -> FileNodeConstants.TYPE_FILE.equals(n.getType()) && name.equals(n.getName()))
                .findFirst().orElse(null);
    }

    private String deleteToTrash(String nodeId, String userId) {
        FileDeleteDto dto = new FileDeleteDto();
        dto.setIds(List.of(nodeId));
        List<OperationResultVo> results = fileRecycleService.deleteToTrash(dto, userId);
        assertEquals("success", results.get(0).getStatus());
        return results.get(0).getNodeId();
    }

    private List<OperationResultVo> restore(String recordId, String userId) {
        return restore(recordId, userId, null);
    }

    private List<OperationResultVo> restore(String recordId, String userId, ConflictStrategy strategy) {
        FileExecuteRestoreDto dto = new FileExecuteRestoreDto();
        RestoreItemDto item = new RestoreItemDto();
        item.setId(recordId);
        if (strategy != null) {
            item.setStrategy(strategy.getCode());
        }
        dto.setItems(List.of(item));
        List<OperationResultVo> results = fileRecycleService.restore(dto, userId);
        assertEquals("success", results.get(0).getStatus());
        return results;
    }

    private void permanentDelete(String recordId, String userId) {
        FilePermanentDeleteDto dto = new FilePermanentDeleteDto();
        dto.setIds(List.of(recordId));
        List<OperationResultVo> results = fileRecycleService.permanentDelete(dto, userId);
        assertEquals("success", results.get(0).getStatus());
    }

    /**
     * 直接落库远程挂载与挂载节点（不经过 service，避免触发挂载同步异步事件）。
     */
    private FileNode createMountNode(String userId) {
        RemoteMount mount = new RemoteMount();
        mount.setId(IdUtil.nextId());
        mount.setUserId(userId);
        mount.setName("mount-" + System.nanoTime());
        mount.setType("webdav");
        mount.setEnabled(0);
        mount.setConfig("{}");
        remoteMountMapper.insert(mount);

        FileNode mountNode = new FileNode();
        mountNode.setUserId(userId);
        mountNode.setParentId(FileNodeConstants.ROOT_ID);
        mountNode.setName(mount.getName());
        mountNode.setType(FileNodeConstants.TYPE_FOLDER);
        mountNode.setSize(0L);
        mountNode.setSourceType(FileNodeConstants.SOURCE_REMOTE);
        mountNode.setRemoteMountId(mount.getId());
        mountNode.setPath(FileNodeConstants.ROOT_ID);
        mountNode.setStatus(1);
        fileMapper.insert(mountNode);
        return mountNode;
    }

    /**
     * 直接落库远程文件镜像节点（大小仅作传输总字节来源，可构造超大值）。
     */
    private FileNode insertRemoteFileNode(FileNode mountNode, String userId, String name, long size) {
        FileNode node = new FileNode();
        node.setUserId(userId);
        node.setParentId(mountNode.getId());
        node.setName(name);
        node.setType(FileNodeConstants.TYPE_FILE);
        node.setSize(size);
        node.setSourceType(FileNodeConstants.SOURCE_REMOTE);
        node.setRemoteMountId(mountNode.getRemoteMountId());
        node.setPath(FilePathUtil.buildChildPath(mountNode));
        node.setStatus(1);
        fileMapper.insert(node);
        return node;
    }

    private TransferTask buildTransferTask(String userId, FileNode source, FileNode mount) {
        TransferItem item = new TransferItem();
        item.setNodeId(source.getId());
        item.setName(source.getName());
        item.setType(FileNodeConstants.TYPE_FILE);
        item.setSize(source.getSize());
        item.setFinalName(source.getName());
        item.setStrategy(ConflictStrategy.OVERWRITE.getCode());

        TransferTask task = new TransferTask();
        task.setId(IdUtil.nextId());
        task.setUserId(userId);
        task.setOpType("copy");
        task.setSourceType(FileNodeConstants.SOURCE_REMOTE);
        task.setTargetType(FileNodeConstants.SOURCE_LOCAL);
        task.setSourceMountId(mount.getRemoteMountId());
        task.setTargetParentId(FileNodeConstants.ROOT_ID);
        task.setStatus(TransferTaskStatus.PENDING.getValue());
        task.setTotalCount(1L);
        task.setSuccessCount(0L);
        task.setFailCount(0L);
        task.setTotalBytes(source.getSize());
        try {
            task.setItems(objectMapper.writeValueAsString(List.of(item)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return task;
    }
}
