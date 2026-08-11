package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.RecycleRecordMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.RestoreItemDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.OperationResultVo;
import com.fleyx.jcloud.util.FileConflictOverwriteHandler;
import com.fleyx.jcloud.util.FileConflictResolver;
import com.fleyx.jcloud.util.FilePathUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回收站恢复执行支撑组件测试。
 */
@ExtendWith(MockitoExtension.class)
class TrashRestoreSupportTest {

    @Mock
    private FileMapper fileMapper;

    @Mock
    private RecycleRecordMapper recycleRecordMapper;

    @Mock
    private StorageSpaceMapper storageSpaceMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private UserSpaceSupport userSpaceSupport;

    @Mock
    private FileChangeEventSupport fileChangeEventSupport;

    @TempDir
    Path tempDir;

    private TrashRestoreSupport trashRestoreSupport;

    @BeforeEach
    void setUp() {
        FileNodeSupport fileNodeSupport = new FileNodeSupport(fileMapper, userMapper);
        FilePathSupport filePathSupport = new FilePathSupport(fileMapper);
        FileConflictResolver conflictResolver = new FileConflictResolver(fileMapper);
        TrashRestorePathSupport trashRestorePathSupport =
                new TrashRestorePathSupport(fileMapper, fileNodeSupport, filePathSupport);
        // 使用真实覆盖处理器：覆盖场景下真实删除被覆盖文件的物理数据，从而验证覆盖的真实副作用
        FileConflictOverwriteHandler overwriteHandler =
                new FileConflictOverwriteHandler(fileMapper, storageSpaceMapper, userMapper);
        trashRestoreSupport = new TrashRestoreSupport(fileMapper, recycleRecordMapper, storageSpaceMapper,
                fileNodeSupport, filePathSupport, userSpaceSupport, conflictResolver, overwriteHandler,
                trashRestorePathSupport, fileChangeEventSupport);
    }

    @Test
    void shouldSkipRestoreFileWhenStrategyIsSkip() {
        RecycleRecord record = buildRecord("r1", "a.txt", "/a.txt");
        when(recycleRecordMapper.selectById("r1")).thenReturn(record);
        User user = buildUser();
        when(userSpaceSupport.requireUser("u1")).thenReturn(user);
        when(storageSpaceMapper.selectById("s1")).thenReturn(buildSpace());
        FileNode existing = new FileNode();
        existing.setId("n1");
        existing.setName("a.txt");
        when(fileMapper.selectList(any())).thenReturn(List.of(existing));

        RestoreItemDto item = new RestoreItemDto();
        item.setId("r1");
        item.setStrategy(ConflictStrategy.SKIP.getCode());
        List<OperationResultVo> results = trashRestoreSupport.doRestore(List.of(item), "u1", null);

        assertEquals(1, results.size());
        assertEquals(FileNodeConstants.STATUS_SKIPPED, results.get(0).getStatus());
        assertEquals("目标位置已存在同名文件", results.get(0).getMessage());
    }

    @Test
    void shouldRecreateMissingParentChainWhenOriginalParentDeleted() throws Exception {
        RecycleRecord record = buildRecord("r1", "a.txt", "/docs/sub/a.txt");
        when(recycleRecordMapper.selectById("r1")).thenReturn(record);
        User user = buildUser();
        user.setStorageSpaceId("s1");
        when(userSpaceSupport.requireUser("u1")).thenReturn(user);
        StorageSpace space = buildSpace();
        when(storageSpaceMapper.selectById("s1")).thenReturn(space);
        when(userMapper.selectById("u1")).thenReturn(user);
        when(fileMapper.selectList(any())).thenReturn(List.of());

        // 模拟 MyBatis-Plus assign_id：insert 时回填节点 ID 并记录到内存表
        Map<String, FileNode> store = new HashMap<>();
        when(fileMapper.insert(any(FileNode.class))).thenAnswer(invocation -> {
            FileNode node = invocation.getArgument(0);
            if (node.getId() == null) {
                node.setId("id-" + node.getName());
            }
            store.put(node.getId(), node);
            return 1;
        });
        when(fileMapper.selectById(anyString())).thenAnswer(invocation -> store.get(invocation.getArgument(0)));
        when(fileMapper.selectBatchIds(any())).thenAnswer(invocation -> {
            Iterable<String> ids = invocation.getArgument(0);
            return store.values().stream().filter(n -> {
                for (String id : ids) {
                    if (id.equals(n.getId())) {
                        return true;
                    }
                }
                return false;
            }).toList();
        });

        // 回收站中的物理文件：删除侧对顶层单文件按纯文件名落盘 → space/trash/tom/r1/a.txt
        Path trashFile = Path.of(space.getPath(), "trash", user.getUsername(), record.getId(), "a.txt");
        Files.createDirectories(trashFile.getParent());
        Files.writeString(trashFile, "hello");

        RestoreItemDto item = new RestoreItemDto();
        item.setId("r1");
        List<OperationResultVo> results = trashRestoreSupport.doRestore(List.of(item), "u1", null);

        assertEquals(1, results.size());
        OperationResultVo result = results.get(0);
        assertEquals(FileNodeConstants.STATUS_SUCCESS, result.getStatus());
        assertNull(result.getNewName());
        assertNotNull(result.getNodeId());

        // 目录链 docs/sub 被补建，文件挂到 sub 下
        FileNode docs = store.get("id-docs");
        FileNode sub = store.get("id-sub");
        assertNotNull(docs);
        assertNotNull(sub);
        assertEquals(FileNodeConstants.ROOT_ID, docs.getParentId());
        assertEquals(docs.getId(), sub.getParentId());
        FileNode restored = store.get(result.getNodeId());
        assertNotNull(restored);
        assertEquals(sub.getId(), restored.getParentId());

        // 物理文件从回收站移动到 files/tom/docs/sub/a.txt
        Path restoredFile = FilePathUtil.resolvePhysicalPath(space, user.getUsername(), "/docs/sub/a.txt");
        assertTrue(Files.exists(restoredFile));
        assertTrue(Files.notExists(trashFile));
        verify(recycleRecordMapper).physicalDeleteById("r1");
    }

    @Test
    void shouldRestoreFolderWithChildren() throws Exception {
        RecycleRecord record = buildRecord("r1", "docs", "folder", "/docs");
        when(recycleRecordMapper.selectById("r1")).thenReturn(record);
        User user = buildUser();
        when(userSpaceSupport.requireUser("u1")).thenReturn(user);
        StorageSpace space = buildSpace();
        when(storageSpaceMapper.selectById("s1")).thenReturn(space);
        when(userMapper.selectById("u1")).thenReturn(user);
        when(fileMapper.selectList(any())).thenReturn(List.of());

        // 模拟 MyBatis-Plus assign_id：insert 时回填节点 ID 并记录到内存表
        Map<String, FileNode> store = new HashMap<>();
        when(fileMapper.insert(any(FileNode.class))).thenAnswer(invocation -> {
            FileNode node = invocation.getArgument(0);
            if (node.getId() == null) {
                node.setId("id-" + node.getName());
            }
            store.put(node.getId(), node);
            return 1;
        });
        when(fileMapper.selectById(anyString())).thenAnswer(invocation -> store.get(invocation.getArgument(0)));

        // 回收站中的物理目录树：space/trash/tom/r1/docs/{sub/a.txt, b.txt}
        Path trashDocs = Path.of(space.getPath(), "trash", user.getUsername(), record.getId(), "docs");
        Files.createDirectories(trashDocs.resolve("sub"));
        Files.writeString(trashDocs.resolve("sub/a.txt"), "hello");
        Files.writeString(trashDocs.resolve("b.txt"), "world");

        RestoreItemDto item = new RestoreItemDto();
        item.setId("r1");
        List<OperationResultVo> results = trashRestoreSupport.doRestore(List.of(item), "u1", null);

        assertEquals(1, results.size());
        OperationResultVo result = results.get(0);
        assertEquals(FileNodeConstants.STATUS_SUCCESS, result.getStatus());
        assertNull(result.getNewName());
        assertEquals("id-docs", result.getNodeId());

        // 顶层文件夹与子文件夹节点均已重建，文件节点挂到正确父目录下
        FileNode docs = store.get("id-docs");
        assertNotNull(docs);
        assertEquals(FileNodeConstants.ROOT_ID, docs.getParentId());
        assertEquals(FileNodeConstants.TYPE_FOLDER, docs.getType());
        FileNode sub = store.get("id-sub");
        assertNotNull(sub);
        assertEquals("id-docs", sub.getParentId());
        FileNode a = store.get("id-a.txt");
        assertNotNull(a);
        assertEquals("id-sub", a.getParentId());
        assertEquals(5L, a.getSize());
        FileNode b = store.get("id-b.txt");
        assertNotNull(b);
        assertEquals("id-docs", b.getParentId());

        // 物理文件从回收站移回 files/tom/docs/，回收站目录树消失
        Path restoredA = FilePathUtil.resolvePhysicalPath(space, user.getUsername(), "/docs/sub/a.txt");
        assertTrue(Files.exists(restoredA));
        assertEquals("hello", Files.readString(restoredA));
        assertTrue(Files.exists(FilePathUtil.resolvePhysicalPath(space, user.getUsername(), "/docs/b.txt")));
        assertTrue(Files.notExists(trashDocs));
        verify(recycleRecordMapper).physicalDeleteById("r1");
    }

    @Test
    void shouldOverwriteExistingFileWhenStrategyIsOverwrite() throws Exception {
        RecycleRecord record = buildRecord("r1", "a.txt", "/a.txt");
        when(recycleRecordMapper.selectById("r1")).thenReturn(record);
        User user = buildUser();
        when(userSpaceSupport.requireUser("u1")).thenReturn(user);
        StorageSpace space = buildSpace();
        when(storageSpaceMapper.selectById("s1")).thenReturn(space);

        // 目标位置已存在同名文件，且物理旧文件真实存在，供覆盖处理器删除
        FileNode existing = new FileNode();
        existing.setId("n1");
        existing.setName("a.txt");
        existing.setType(FileNodeConstants.TYPE_FILE);
        existing.setUserId("u1");
        existing.setParentId(FileNodeConstants.ROOT_ID);
        existing.setPath(FileNodeConstants.ROOT_ID);
        existing.setStorageSpaceId("s1");
        existing.setSize(3L);
        when(fileMapper.selectList(any())).thenReturn(List.of(existing));

        Map<String, FileNode> store = new HashMap<>();
        when(fileMapper.insert(any(FileNode.class))).thenAnswer(invocation -> {
            FileNode node = invocation.getArgument(0);
            if (node.getId() == null) {
                node.setId("id-" + node.getName());
            }
            store.put(node.getId(), node);
            return 1;
        });

        Path oldFile = FilePathUtil.resolvePhysicalPath(space, user.getUsername(), "/a.txt");
        Files.createDirectories(oldFile.getParent());
        Files.writeString(oldFile, "old");
        Path trashFile = Path.of(space.getPath(), "trash", user.getUsername(), record.getId(), "a.txt");
        Files.createDirectories(trashFile.getParent());
        Files.writeString(trashFile, "new");

        RestoreItemDto item = new RestoreItemDto();
        item.setId("r1");
        item.setStrategy(ConflictStrategy.OVERWRITE.getCode());
        List<OperationResultVo> results = trashRestoreSupport.doRestore(List.of(item), "u1", null);

        assertEquals(1, results.size());
        OperationResultVo result = results.get(0);
        assertEquals(FileNodeConstants.STATUS_SUCCESS, result.getStatus());
        assertNull(result.getNewName());
        assertNotNull(result.getNodeId());

        // 旧文件被覆盖处理器物理删除后，新文件移动到位（若覆盖处理器未生效，move 会因目标已存在而失败）
        assertEquals("new", Files.readString(oldFile));
        assertTrue(Files.notExists(trashFile));
        FileNode restored = store.get("id-a.txt");
        assertNotNull(restored);
        assertEquals("a.txt", restored.getName());
        assertEquals(3L, restored.getSize());
        assertEquals(FileNodeConstants.ROOT_ID, restored.getParentId());
        verify(fileMapper).deleteById(existing);
        verify(recycleRecordMapper).physicalDeleteById("r1");
    }

    @Test
    void shouldKeepBothWhenNameConflictAndStrategyIsKeep() throws Exception {
        RecycleRecord record = buildRecord("r1", "a.txt", "/a.txt");
        when(recycleRecordMapper.selectById("r1")).thenReturn(record);
        User user = buildUser();
        when(userSpaceSupport.requireUser("u1")).thenReturn(user);
        StorageSpace space = buildSpace();
        when(storageSpaceMapper.selectById("s1")).thenReturn(space);

        FileNode existing = new FileNode();
        existing.setId("n1");
        existing.setName("a.txt");
        existing.setType(FileNodeConstants.TYPE_FILE);
        existing.setUserId("u1");
        existing.setParentId(FileNodeConstants.ROOT_ID);
        existing.setPath(FileNodeConstants.ROOT_ID);
        existing.setStorageSpaceId("s1");
        existing.setSize(3L);
        // 前三次同名查询命中冲突节点（resolveRestoreName / generateKeepName / 后缀序号计算），
        // 第四次按重命名结果 a(1).txt 查询不再冲突
        when(fileMapper.selectList(any())).thenReturn(List.of(existing), List.of(existing),
                List.of(existing), List.of());

        Map<String, FileNode> store = new HashMap<>();
        when(fileMapper.insert(any(FileNode.class))).thenAnswer(invocation -> {
            FileNode node = invocation.getArgument(0);
            if (node.getId() == null) {
                node.setId("id-" + node.getName());
            }
            store.put(node.getId(), node);
            return 1;
        });

        Path oldFile = FilePathUtil.resolvePhysicalPath(space, user.getUsername(), "/a.txt");
        Files.createDirectories(oldFile.getParent());
        Files.writeString(oldFile, "old");
        Path trashFile = Path.of(space.getPath(), "trash", user.getUsername(), record.getId(), "a.txt");
        Files.createDirectories(trashFile.getParent());
        Files.writeString(trashFile, "new");

        RestoreItemDto item = new RestoreItemDto();
        item.setId("r1");
        item.setStrategy(ConflictStrategy.KEEP.getCode());
        List<OperationResultVo> results = trashRestoreSupport.doRestore(List.of(item), "u1", null);

        assertEquals(1, results.size());
        OperationResultVo result = results.get(0);
        assertEquals(FileNodeConstants.STATUS_SUCCESS, result.getStatus());
        assertEquals("a(1).txt", result.getNewName());

        // 旧文件保留不动，新文件以 a(1).txt 落盘
        assertEquals("old", Files.readString(oldFile));
        Path restored = FilePathUtil.resolvePhysicalPath(space, user.getUsername(), "/a(1).txt");
        assertTrue(Files.exists(restored));
        assertEquals("new", Files.readString(restored));
        assertTrue(Files.notExists(trashFile));
        FileNode node = store.get("id-a(1).txt");
        assertNotNull(node);
        assertEquals("a(1).txt", node.getName());
        verify(recycleRecordMapper).physicalDeleteById("r1");
    }

    @Test
    void shouldReturnFailedResultWhenStorageSpaceMissing() {
        RecycleRecord record = buildRecord("r1", "a.txt", "/a.txt");
        when(recycleRecordMapper.selectById("r1")).thenReturn(record);
        User user = buildUser();
        when(userSpaceSupport.requireUser("u1")).thenReturn(user);
        // storageSpaceMapper.selectById 默认返回 null，命中 TrashRestoreSupport#restoreOne 的 failedResult 分支

        RestoreItemDto item = new RestoreItemDto();
        item.setId("r1");
        List<OperationResultVo> results = trashRestoreSupport.doRestore(List.of(item), "u1", null);

        assertEquals(1, results.size());
        OperationResultVo result = results.get(0);
        assertEquals(FileNodeConstants.STATUS_FAILED, result.getStatus());
        assertEquals("存储空间不存在", result.getMessage());
        assertEquals("r1", result.getSourceId());
        assertEquals("a.txt", result.getSourceName());
        verify(recycleRecordMapper, never()).physicalDeleteById(anyString());
        verify(fileMapper, never()).insert(any(FileNode.class));
    }

    @Test
    void shouldThrowSystemExceptionWhenFolderMoveFails() throws Exception {
        RecycleRecord record = buildRecord("r1", "docs", "folder", "/docs");
        when(recycleRecordMapper.selectById("r1")).thenReturn(record);
        User user = buildUser();
        when(userSpaceSupport.requireUser("u1")).thenReturn(user);
        StorageSpace space = buildSpace();
        when(storageSpaceMapper.selectById("s1")).thenReturn(space);
        when(fileMapper.selectList(any())).thenReturn(List.of());

        // 回收站中的目录树
        Path trashDocs = Path.of(space.getPath(), "trash", user.getUsername(), record.getId(), "docs");
        Files.createDirectories(trashDocs);
        Files.writeString(trashDocs.resolve("a.txt"), "x");
        // 目标位置已存在非空目录：Files.move 抛出 DirectoryNotEmptyException
        Path targetTop = FilePathUtil.resolvePhysicalPath(space, user.getUsername(), "/docs");
        Files.createDirectories(targetTop);
        Files.writeString(targetTop.resolve("existing.txt"), "keep");

        RestoreItemDto item = new RestoreItemDto();
        item.setId("r1");
        SystemException ex = assertThrows(SystemException.class,
                () -> trashRestoreSupport.doRestore(List.of(item), "u1", null));

        assertEquals(ResultCode.BUSINESS_ERROR, ex.getResultCode());
        assertTrue(ex.getMessage().contains("恢复文件夹失败"));
        assertNotNull(ex.getCause());

        // 失败无副作用：回收站物理文件仍在，无任何 DB 写入
        assertTrue(Files.exists(trashDocs.resolve("a.txt")));
        verify(recycleRecordMapper, never()).physicalDeleteById(anyString());
        verify(fileMapper, never()).insert(any(FileNode.class));
    }

    private RecycleRecord buildRecord(String id, String name, String originalPathName) {
        return buildRecord(id, name, "file", originalPathName);
    }

    private RecycleRecord buildRecord(String id, String name, String type, String originalPathName) {
        RecycleRecord record = new RecycleRecord();
        record.setId(id);
        record.setUserId("u1");
        record.setName(name);
        record.setType(type);
        record.setOriginalPathName(originalPathName);
        return record;
    }

    private User buildUser() {
        User user = new User();
        user.setId("u1");
        user.setUsername("tom");
        user.setStorageSpaceId("s1");
        return user;
    }

    private StorageSpace buildSpace() {
        StorageSpace space = new StorageSpace();
        space.setId("s1");
        space.setPath(tempDir.toString());
        return space;
    }
}
