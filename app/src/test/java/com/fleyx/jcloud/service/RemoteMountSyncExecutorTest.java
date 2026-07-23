package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.bo.RemoteFileEntry;
import com.fleyx.jcloud.model.dto.RemoteMountSaveDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.vo.RemoteMountVo;
import com.fleyx.jcloud.model.vo.RemoteSyncTaskVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.impl.RemoteMountSyncExecutor;
import com.fleyx.jcloud.service.impl.RemoteProtocolAdapterFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 远程挂载同步任务执行器测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RemoteMountSyncExecutorTest {

    @Autowired
    private RemoteMountSyncExecutor executor;

    @Autowired
    private RemoteMountService remoteMountService;

    @Autowired
    private RemoteMountSyncService remoteMountSyncService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private FileMapper fileMapper;

    @MockitoBean
    private RemoteProtocolAdapterFactory adapterFactory;

    private RemoteProtocolAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = Mockito.mock(RemoteProtocolAdapter.class);
        when(adapterFactory.create(any())).thenReturn(adapter);
    }

    @Test
    void shouldSyncRemoteEntriesToDatabase() {
        UserVo user = prepareUser();
        RemoteMountVo mount = createMount(user.getId());

        when(adapter.listChildren("/")).thenReturn(List.of(
                buildEntry("/docs", "docs", true, 0L, 1000L, null),
                buildEntry("/photo.png", "photo.png", false, 6L, 1000L, "etag-photo")));
        when(adapter.listChildren("/docs")).thenReturn(List.of(
                buildEntry("/docs/readme.md", "readme.md", false, 8L, 1000L, "etag-readme")));

        RemoteSyncTaskVo task = remoteMountSyncService.submitImmediate(mount.getId(), user.getId());
        executor.execute(task.getId());

        RemoteSyncTaskVo completed = remoteMountSyncService.getLatestTask(mount.getId(), user.getId());
        assertEquals("COMPLETED", completed.getStatus());

        FileNode mountNode = findMountNode(user.getId(), mount.getId());
        List<FileNode> roots = fileMapper.selectByParentId(user.getId(), mountNode.getId());
        assertEquals(2, roots.size());

        FileNode docs = roots.stream().filter(n -> "docs".equals(n.getName())).findFirst().orElseThrow();
        assertEquals("folder", docs.getType());
        assertEquals("remote", docs.getSourceType());
        assertEquals(mount.getId(), docs.getRemoteMountId());

        List<FileNode> docsChildren = fileMapper.selectByParentId(user.getId(), docs.getId());
        assertEquals(1, docsChildren.size());
        assertEquals("readme.md", docsChildren.get(0).getName());
        assertEquals("file", docsChildren.get(0).getType());

        FileNode photo = roots.stream().filter(n -> "photo.png".equals(n.getName())).findFirst().orElseThrow();
        assertEquals("file", photo.getType());
        assertEquals(Long.valueOf(6L), photo.getSize());
        assertEquals("etag-photo", photo.getHash());
    }

    @Test
    void shouldDeleteDatabaseNodesWhenRemoteRemoved() {
        UserVo user = prepareUser();
        RemoteMountVo mount = createMount(user.getId());

        when(adapter.listChildren("/")).thenReturn(List.of(
                buildEntry("/old.txt", "old.txt", false, 3L, 1000L, "etag-old")));
        RemoteSyncTaskVo first = remoteMountSyncService.submitImmediate(mount.getId(), user.getId());
        executor.execute(first.getId());

        FileNode mountNode = findMountNode(user.getId(), mount.getId());
        assertEquals(1, fileMapper.selectByParentId(user.getId(), mountNode.getId()).size());

        // 远端清空后再同步，DB 中对应节点应被删除
        when(adapter.listChildren("/")).thenReturn(List.of());
        RemoteSyncTaskVo second = remoteMountSyncService.submitImmediate(mount.getId(), user.getId());
        executor.execute(second.getId());

        RemoteSyncTaskVo completed = remoteMountSyncService.getLatestTask(mount.getId(), user.getId());
        assertEquals("COMPLETED", completed.getStatus());
        assertTrue(fileMapper.selectByParentId(user.getId(), mountNode.getId()).isEmpty());
    }

    @Test
    void shouldUpdateChangedFileMetadata() {
        UserVo user = prepareUser();
        RemoteMountVo mount = createMount(user.getId());

        when(adapter.listChildren("/")).thenReturn(List.of(
                buildEntry("/note.txt", "note.txt", false, 8L, 1000L, "etag-v1")));
        RemoteSyncTaskVo first = remoteMountSyncService.submitImmediate(mount.getId(), user.getId());
        executor.execute(first.getId());

        FileNode mountNode = findMountNode(user.getId(), mount.getId());
        FileNode note = fileMapper.selectByParentId(user.getId(), mountNode.getId()).get(0);
        String nodeId = note.getId();

        // 远端同名文件元数据变化后再同步，应更新既有节点
        when(adapter.listChildren("/")).thenReturn(List.of(
                buildEntry("/note.txt", "note.txt", false, 15L, 2000L, "etag-v2")));
        RemoteSyncTaskVo second = remoteMountSyncService.submitImmediate(mount.getId(), user.getId());
        executor.execute(second.getId());

        RemoteSyncTaskVo completed = remoteMountSyncService.getLatestTask(mount.getId(), user.getId());
        assertEquals("COMPLETED", completed.getStatus());

        FileNode updated = fileMapper.selectById(nodeId);
        assertNotNull(updated);
        assertEquals(Long.valueOf(15L), updated.getSize());
        assertEquals("etag-v2", updated.getHash());
        assertEquals(Long.valueOf(2000L), updated.getLastModified());
    }

    private RemoteFileEntry buildEntry(String remotePath, String name, boolean folder,
                                       long size, long lastModified, String etag) {
        RemoteFileEntry entry = new RemoteFileEntry();
        entry.setRemotePath(remotePath);
        entry.setName(name);
        entry.setFolder(folder);
        entry.setSize(size);
        entry.setLastModified(lastModified);
        entry.setEtag(etag);
        return entry;
    }

    private FileNode findMountNode(String userId, String mountId) {
        return fileMapper.selectByParentId(userId, FileNodeConstants.ROOT_ID).stream()
                .filter(n -> mountId.equals(n.getRemoteMountId()))
                .findFirst()
                .orElseThrow();
    }

    private RemoteMountVo createMount(String userId) {
        RemoteMountSaveDto dto = new RemoteMountSaveDto();
        dto.setName("mount-" + System.nanoTime());
        dto.setType("webdav");
        dto.setUrl("http://example.com/dav");
        dto.setUsername("user");
        dto.setPassword("pass");
        dto.setEnabled(0);
        return remoteMountService.save(dto, userId);
    }

    private UserVo prepareUser() {
        try {
            Path spacePath = Files.createTempDirectory("remote-sync-exec-space-");
            StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
            spaceDto.setName("remote-sync-exec-space-" + System.nanoTime());
            spaceDto.setPath(spacePath.toString());
            StorageSpaceVo space = storageSpaceService.save(spaceDto);

            UserSaveDto dto = new UserSaveDto();
            dto.setUsername("remote_sync_user_" + System.nanoTime());
            dto.setPassword("123456");
            dto.setStorageSpaceId(space.getId());
            dto.setQuota(1L);
            dto.setQuotaUnit("GB");
            return userService.saveUser(dto);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
