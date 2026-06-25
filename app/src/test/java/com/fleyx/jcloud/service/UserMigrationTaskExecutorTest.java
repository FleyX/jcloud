package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserMigrationSubmitDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.dto.UserStorageDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserMigrationTaskVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.impl.UserMigrationTaskExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户存储空间迁移任务执行器测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserMigrationTaskExecutorTest {

    @Autowired
    private UserMigrationTaskExecutor executor;

    @Autowired
    private UserMigrationService userMigrationService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private FileService fileService;

    @Autowired
    private com.fleyx.jcloud.mapper.UserMapper userMapper;

    @Autowired
    private com.fleyx.jcloud.mapper.FileMapper fileMapper;

    @Test
    void shouldMigrateFilesAndUpdateUserSpace() throws Exception {
        String testId = String.valueOf(System.nanoTime());
        Path sourcePath = Files.createTempDirectory("jcloud-source-" + testId);
        Path targetPath = Files.createTempDirectory("jcloud-target-" + testId);

        StorageSpaceSaveDto sourceDto = buildSpaceDto("source-" + testId, sourcePath.toString());
        StorageSpaceVo sourceSpace = storageSpaceService.save(sourceDto);

        StorageSpaceSaveDto targetDto = buildSpaceDto("target-" + testId, targetPath.toString());
        targetDto.setCapacity(214748364800L);
        StorageSpaceVo targetSpace = storageSpaceService.save(targetDto);

        UserSaveDto userDto = new UserSaveDto();
        userDto.setUsername("migrateExec" + testId);
        userDto.setPassword("123456");
        UserVo user = userService.saveUser(userDto);

        UserStorageDto bindDto = new UserStorageDto();
        bindDto.setUserId(user.getId());
        bindDto.setStorageSpaceId(sourceSpace.getId());
        bindDto.setQuota(10737418240L);
        userService.bindStorageSpace(bindDto);

        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "hello.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        FileNodeVo uploaded = fileService.upload(multipartFile, user.getId());
        assertNotNull(uploaded.getId());

        UserMigrationSubmitDto submitDto = new UserMigrationSubmitDto();
        submitDto.setUserId(user.getId());
        submitDto.setTargetSpaceId(targetSpace.getId());
        submitDto.setNewQuota(21474836480L);
        UserMigrationTaskVo task = userMigrationService.submitMigration(submitDto);

        executor.execute(task.getId());

        UserMigrationTaskVo completed = userMigrationService.getLatestTaskByUserId(user.getId());
        assertEquals("COMPLETED", completed.getStatus());
        assertEquals(5L, completed.getTotalBytes());
        assertEquals(5L, completed.getMigratedBytes());

        User migratedUser = userMapper.selectById(user.getId());
        assertEquals(targetSpace.getId(), migratedUser.getStorageSpaceId());
        assertEquals(Long.valueOf(21474836480L), migratedUser.getQuota());
        assertEquals(Integer.valueOf(0), migratedUser.getReadOnly());

        FileNode fileNode = fileMapper.selectById(uploaded.getId());
        assertEquals(targetSpace.getId(), fileNode.getStorageSpaceId());

        Path targetFile = targetPath.resolve(user.getId().toString()).resolve("files").resolve("hello.txt");
        assertTrue(Files.exists(targetFile), "文件应已复制到目标存储空间");
        assertEquals("hello", Files.readString(targetFile));
    }

    @Test
    void shouldRollbackOnCopyFailure() throws Exception {
        String testId = String.valueOf(System.nanoTime());
        Path sourcePath = Files.createTempDirectory("jcloud-source-" + testId);
        Path targetAsFile = Files.createTempFile("jcloud-target-file-" + testId, "");

        StorageSpaceSaveDto sourceDto = buildSpaceDto("source-" + testId, sourcePath.toString());
        StorageSpaceVo sourceSpace = storageSpaceService.save(sourceDto);

        StorageSpaceSaveDto targetDto = buildSpaceDto("target-" + testId, targetAsFile.toString());
        targetDto.setCapacity(214748364800L);
        StorageSpaceVo targetSpace = storageSpaceService.save(targetDto);

        UserSaveDto userDto = new UserSaveDto();
        userDto.setUsername("migrateRollback" + testId);
        userDto.setPassword("123456");
        UserVo user = userService.saveUser(userDto);

        UserStorageDto bindDto = new UserStorageDto();
        bindDto.setUserId(user.getId());
        bindDto.setStorageSpaceId(sourceSpace.getId());
        bindDto.setQuota(10737418240L);
        userService.bindStorageSpace(bindDto);

        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "hello.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        FileNodeVo uploaded = fileService.upload(multipartFile, user.getId());

        UserMigrationSubmitDto submitDto = new UserMigrationSubmitDto();
        submitDto.setUserId(user.getId());
        submitDto.setTargetSpaceId(targetSpace.getId());
        submitDto.setNewQuota(21474836480L);
        UserMigrationTaskVo task = userMigrationService.submitMigration(submitDto);

        executor.execute(task.getId());

        UserMigrationTaskVo failed = userMigrationService.getLatestTaskByUserId(user.getId());
        assertEquals("FAILED", failed.getStatus());
        assertNotNull(failed.getErrorMsg());

        User rolledBackUser = userMapper.selectById(user.getId());
        assertEquals(sourceSpace.getId(), rolledBackUser.getStorageSpaceId());
        assertEquals(Integer.valueOf(0), rolledBackUser.getReadOnly());

        FileNode fileNode = fileMapper.selectById(uploaded.getId());
        assertEquals(sourceSpace.getId(), fileNode.getStorageSpaceId());

        Path sourceFile = sourcePath.resolve(user.getId().toString()).resolve("files").resolve("hello.txt");
        assertTrue(Files.exists(sourceFile), "源文件应保留");
    }

    private StorageSpaceSaveDto buildSpaceDto(String name, String path) {
        StorageSpaceSaveDto dto = new StorageSpaceSaveDto();
        dto.setName(name);
        dto.setPath(path);
        dto.setType("USER");
        dto.setCapacity(107374182400L);
        return dto;
    }
}
