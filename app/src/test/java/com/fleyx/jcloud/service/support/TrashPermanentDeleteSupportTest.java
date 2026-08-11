package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.mapper.RecycleRecordMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.OperationResultVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回收站彻底删除支撑组件测试。
 */
@ExtendWith(MockitoExtension.class)
class TrashPermanentDeleteSupportTest {

    @Mock
    private RecycleRecordMapper recycleRecordMapper;

    @Mock
    private StorageSpaceMapper storageSpaceMapper;

    @Mock
    private UserSpaceSupport userSpaceSupport;

    @Mock
    private FileChangeEventSupport fileChangeEventSupport;

    @InjectMocks
    private TrashPermanentDeleteSupport trashPermanentDeleteSupport;

    @TempDir
    Path tempDir;

    @Test
    void shouldFailWhenRecordNotFound() {
        when(recycleRecordMapper.selectById("r1")).thenReturn(null);

        List<OperationResultVo> results = trashPermanentDeleteSupport.doPermanentDelete(List.of("r1"), "u1");

        assertEquals(1, results.size());
        assertEquals(FileNodeConstants.STATUS_FAILED, results.get(0).getStatus());
        assertEquals("记录不存在或无权限", results.get(0).getMessage());
    }

    @Test
    void shouldFailWhenRecordOwnedByOtherUser() {
        RecycleRecord record = buildRecord("r1", "u2");
        when(recycleRecordMapper.selectById("r1")).thenReturn(record);

        List<OperationResultVo> results = trashPermanentDeleteSupport.doPermanentDelete(List.of("r1"), "u1");

        assertEquals(FileNodeConstants.STATUS_FAILED, results.get(0).getStatus());
        assertEquals("记录不存在或无权限", results.get(0).getMessage());
    }

    @Test
    void shouldFailWhenStorageSpaceMissing() {
        RecycleRecord record = buildRecord("r1", "u1");
        when(recycleRecordMapper.selectById("r1")).thenReturn(record);
        User user = buildUser();
        when(userSpaceSupport.requireUser("u1")).thenReturn(user);
        when(storageSpaceMapper.selectById("s1")).thenReturn(null);

        List<OperationResultVo> results = trashPermanentDeleteSupport.doPermanentDelete(List.of("r1"), "u1");

        assertEquals(FileNodeConstants.STATUS_FAILED, results.get(0).getStatus());
        assertEquals("用户未绑定存储空间", results.get(0).getMessage());
    }

    @Test
    void shouldFailWhenPhysicalDeleteThrows() {
        RecycleRecord record = buildRecord("r1", "u1");
        when(recycleRecordMapper.selectById("r1")).thenReturn(record);
        User user = buildUser();
        when(userSpaceSupport.requireUser("u1")).thenReturn(user);
        StorageSpace space = buildSpace();
        when(storageSpaceMapper.selectById("s1")).thenReturn(space);

        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class)) {
            files.when(() -> Files.exists(any(Path.class))).thenReturn(true);
            files.when(() -> Files.walk(any(Path.class))).thenThrow(new IOException("boom"));

            List<OperationResultVo> results = trashPermanentDeleteSupport.doPermanentDelete(List.of("r1"), "u1");

            assertEquals(FileNodeConstants.STATUS_FAILED, results.get(0).getStatus());
            assertEquals("物理文件删除失败: boom", results.get(0).getMessage());
        }
    }

    @Test
    void shouldPermanentDeleteWhenTrashDirAbsent() {
        RecycleRecord record = buildRecord("r1", "u1");
        when(recycleRecordMapper.selectById("r1")).thenReturn(record);
        User user = buildUser();
        when(userSpaceSupport.requireUser("u1")).thenReturn(user);
        StorageSpace space = buildSpace();
        when(storageSpaceMapper.selectById("s1")).thenReturn(space);

        List<OperationResultVo> results = trashPermanentDeleteSupport.doPermanentDelete(List.of("r1"), "u1");

        assertEquals(FileNodeConstants.STATUS_SUCCESS, results.get(0).getStatus());
        assertEquals("已永久删除", results.get(0).getMessage());
        verify(userSpaceSupport).updateUsedSpace(user, space, -100L);
        verify(recycleRecordMapper).physicalDeleteById("r1");
    }

    private RecycleRecord buildRecord(String id, String userId) {
        RecycleRecord record = new RecycleRecord();
        record.setId(id);
        record.setUserId(userId);
        record.setName("a.txt");
        record.setTotalSize(100L);
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
