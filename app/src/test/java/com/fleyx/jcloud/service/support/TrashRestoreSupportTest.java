package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    private FileConflictOverwriteHandler overwriteHandler;

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
        trashRestoreSupport = new TrashRestoreSupport(fileMapper, recycleRecordMapper, storageSpaceMapper,
                fileNodeSupport, filePathSupport, userSpaceSupport, conflictResolver, overwriteHandler,
                trashRestorePathSupport);
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

        // 回收站中的物理文件：space/trash/tom/r1/docs/sub/a.txt
        Path trashFile = Path.of(space.getPath(), "trash", user.getUsername(), record.getId(),
                "docs", "sub", "a.txt");
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

    private RecycleRecord buildRecord(String id, String name, String originalPathName) {
        RecycleRecord record = new RecycleRecord();
        record.setId(id);
        record.setUserId("u1");
        record.setName(name);
        record.setType("file");
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
