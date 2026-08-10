package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.constant.StorageConstant;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserSyncTaskVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.impl.UserSyncExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户存储空间同步任务执行器测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserSyncExecutorTest {

    @Autowired
    private UserSyncExecutor executor;

    @Autowired
    private UserSyncService userSyncService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private FileService fileService;

    @Autowired
    private FileMapper fileMapper;

    @TempDir
    Path tempDir;

    @Test
    void shouldSyncPhysicalFilesToDatabase() throws Exception {
        UserSpacePrepared prepared = prepareUserSpace();
        Path filesDir = prepared.filesDir();

        // 物理目录：/docs/readme.md + /photo.png
        Path docsDir = filesDir.resolve("docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("readme.md"), "# hello");
        Files.writeString(filesDir.resolve("photo.png"), "binary");

        UserSyncTaskVo task = userSyncService.submitImmediate(prepared.user().getId());
        executor.execute(task.getId());

        UserSyncTaskVo completed = userSyncService.getLatestTask(prepared.user().getId());
        assertEquals("COMPLETED", completed.getStatus());

        List<FileNode> roots = fileMapper.selectByParentId(prepared.user().getId(), FileNodeConstants.ROOT_ID);
        assertEquals(2, roots.size());

        FileNode docs = roots.stream().filter(n -> "docs".equals(n.getName())).findFirst().orElseThrow();
        assertEquals("folder", docs.getType());

        List<FileNode> docsChildren = fileMapper.selectByParentId(prepared.user().getId(), docs.getId());
        assertEquals(1, docsChildren.size());
        assertEquals("readme.md", docsChildren.get(0).getName());
        assertEquals("file", docsChildren.get(0).getType());

        FileNode photo = roots.stream().filter(n -> "photo.png".equals(n.getName())).findFirst().orElseThrow();
        assertEquals("file", photo.getType());
        assertEquals(Long.valueOf(6L), photo.getSize());
        assertTrue(photo.getLastModified() > 0);
    }

    @Test
    void shouldDeleteDatabaseNodesWhenPhysicalRemoved() throws Exception {
        UserSpacePrepared prepared = prepareUserSpace();
        String userId = prepared.user().getId();

        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "old.txt", "text/plain", "old".getBytes(StandardCharsets.UTF_8));
        FileNodeVo uploaded = fileService.upload(multipartFile, userId, FileNodeConstants.ROOT_ID, null);
        assertNotNull(uploaded.getId());

        // 删除物理文件后再同步，DB 中对应节点应被删除
        Files.deleteIfExists(prepared.filesDir().resolve("old.txt"));

        UserSyncTaskVo task = userSyncService.submitImmediate(userId);
        executor.execute(task.getId());

        UserSyncTaskVo completed = userSyncService.getLatestTask(userId);
        assertEquals("COMPLETED", completed.getStatus());

        List<FileNode> roots = fileMapper.selectByParentId(userId, FileNodeConstants.ROOT_ID);
        assertTrue(roots.isEmpty());
    }

    @Test
    void shouldUpdateChangedFileMetadata() throws Exception {
        UserSpacePrepared prepared = prepareUserSpace();
        String userId = prepared.user().getId();
        Path filesDir = prepared.filesDir();

        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "note.txt", "text/plain", "original".getBytes(StandardCharsets.UTF_8));
        FileNodeVo uploaded = fileService.upload(multipartFile, userId, FileNodeConstants.ROOT_ID, null);
        String nodeId = uploaded.getId();

        // 覆盖物理文件，改变内容与大小，并显式设置一个与 DB 记录明显不同的 mtime，消除毫秒级时序依赖
        Path physicalFile = filesDir.resolve("note.txt");
        Files.writeString(physicalFile, "updated content");
        Files.setLastModifiedTime(physicalFile, FileTime.fromMillis(System.currentTimeMillis() + 60_000));

        UserSyncTaskVo task = userSyncService.submitImmediate(userId);
        executor.execute(task.getId());

        UserSyncTaskVo completed = userSyncService.getLatestTask(userId);
        assertEquals("COMPLETED", completed.getStatus());

        FileNode updated = fileMapper.selectById(nodeId);
        assertEquals(Long.valueOf(15L), updated.getSize());
        assertNull(updated.getHash());
    }

    private UserSpacePrepared prepareUserSpace() throws Exception {
        Path spacePath = Files.createTempDirectory(tempDir, "sync-exec-space-");
        StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
        spaceDto.setName("sync-exec-space-" + System.nanoTime());
        spaceDto.setPath(spacePath.toString());
        StorageSpaceVo space = storageSpaceService.save(spaceDto);

        UserSaveDto dto = new UserSaveDto();
        dto.setUsername("sync_exec_user_" + System.nanoTime());
        dto.setPassword("123456");
        dto.setStorageSpaceId(space.getId());
        dto.setQuota(1L);
        dto.setQuotaUnit("GB");
        UserVo user = userService.saveUser(dto);

        Path filesDir = spacePath.resolve(StorageConstant.FILES_DIR).resolve(user.getUsername());
        Files.createDirectories(filesDir);
        return new UserSpacePrepared(user, space, filesDir);
    }

    private record UserSpacePrepared(UserVo user, StorageSpaceVo space, Path filesDir) {
    }
}
