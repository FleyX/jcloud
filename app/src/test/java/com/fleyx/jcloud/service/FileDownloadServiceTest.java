package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.enums.FileZipTaskStatus;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.bo.BatchDownloadResult;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.bo.FileZipTask;
import com.fleyx.jcloud.model.dto.FileBatchDownloadDto;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.dto.OperationItemDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.dto.UserStorageDto;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.OperationResultVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.impl.FileDownloadServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Field;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量下载服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "jcloud.download.zip-stream-threshold-size=104857600",
        "jcloud.download.zip-stream-threshold-count=100"
})
@Transactional
class FileDownloadServiceTest {

    @TestConfiguration
    static class SyncExecutorConfig {

        @Bean(name = "applicationTaskExecutor")
        @Primary
        public TaskExecutor applicationTaskExecutor() {
            return new SyncTaskExecutor();
        }
    }

    @Autowired
    private FileDownloadService fileDownloadService;

    @Autowired
    private FileService fileService;

    @Autowired
    private FileOperationService fileOperationService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private SystemConfigService systemConfigService;

    @TempDir
    Path tempDir;

    @Test
    void shouldDownloadSingleFileAsZip() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = fileService.upload(buildFile("hello.txt", "Hello"), user.getId());

        FileBatchDownloadDto dto = new FileBatchDownloadDto();
        dto.setIds(List.of(file.getId()));

        BatchDownloadResult result = fileDownloadService.downloadBatch(dto, user.getId());

        assertTrue(result instanceof BatchDownloadResult.StreamResult);
        BatchDownloadResult.StreamResult streamResult = (BatchDownloadResult.StreamResult) result;
        assertEquals("archive.zip", streamResult.fileName());
        assertTrue(streamResult.totalSize() > 0);

        try (InputStream is = streamResult.inputStream();
             ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry = zis.getNextEntry();
            assertNotNull(entry);
            assertEquals("hello.txt", entry.getName());
            String content = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("Hello", content);
        }
    }

    @Test
    void shouldDownloadFolderRecursivelyAsZip() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo docs = createFolder(user.getId(), "docs", 0L);
        FileNodeVo report = fileService.upload(buildFile("report.txt", "Report"), user.getId());
        moveFileToFolder(user.getId(), report, docs);

        FileBatchDownloadDto dto = new FileBatchDownloadDto();
        dto.setIds(List.of(docs.getId()));

        BatchDownloadResult result = fileDownloadService.downloadBatch(dto, user.getId());

        assertTrue(result instanceof BatchDownloadResult.StreamResult);
        try (InputStream is = ((BatchDownloadResult.StreamResult) result).inputStream();
             ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry = zis.getNextEntry();
            assertNotNull(entry);
            assertEquals("docs/report.txt", entry.getName());
            assertEquals("Report", new String(zis.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void shouldCreateAsyncTaskWhenExceedThreshold() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = fileService.upload(buildFile("big.txt", "Large content"), user.getId());

        // 临时把阈值降到 1 字节，强制走异步任务
        setField(fileDownloadService, "streamThresholdSize", 1L);

        FileBatchDownloadDto dto = new FileBatchDownloadDto();
        dto.setIds(List.of(file.getId()));

        BatchDownloadResult result = fileDownloadService.downloadBatch(dto, user.getId());

        assertTrue(result instanceof BatchDownloadResult.TaskResult);
        BatchDownloadResult.TaskResult taskResult = (BatchDownloadResult.TaskResult) result;
        assertEquals(FileZipTaskStatus.COMPLETED, taskResult.status());

        FileDownloadResult downloadResult = fileDownloadService.downloadTaskResult(taskResult.taskId(), user.getId());
        assertEquals("archive.zip", downloadResult.getFileName());
        try (InputStream is = downloadResult.getInputStream();
             ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry = zis.getNextEntry();
            assertNotNull(entry);
            assertEquals("big.txt", entry.getName());
        }
    }

    @Test
    void shouldRejectOtherUserTaskAccess() {
        UserVo userA = prepareUserWithStorageSpace().user();
        UserVo userB = prepareUserWithStorageSpace().user();
        FileNodeVo fileA = fileService.upload(buildFile("a.txt", "A"), userA.getId());

        setField(fileDownloadService, "streamThresholdSize", 0L);
        BatchDownloadResult result = fileDownloadService.downloadBatch(
                newBatchDto(fileA.getId()), userA.getId());
        String taskId = ((BatchDownloadResult.TaskResult) result).taskId();

        assertThrows(BusinessException.class,
                () -> fileDownloadService.getTask(taskId, userB.getId()));
        assertThrows(BusinessException.class,
                () -> fileDownloadService.downloadTaskResult(taskId, userB.getId()));
    }

    @Test
    void shouldRejectEmptyIds() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileBatchDownloadDto dto = new FileBatchDownloadDto();
        dto.setIds(List.of());

        assertThrows(BusinessException.class,
                () -> fileDownloadService.downloadBatch(dto, user.getId()));
    }

    private FileBatchDownloadDto newBatchDto(Long... ids) {
        FileBatchDownloadDto dto = new FileBatchDownloadDto();
        dto.setIds(List.of(ids));
        return dto;
    }

    private FileNodeVo createFolder(Long userId, String name, Long parentId) {
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(parentId);
        dto.setName(name);
        return fileOperationService.createFolder(dto, userId);
    }

    private void moveFileToFolder(Long userId, FileNodeVo file, FileNodeVo folder) {
        FileExecuteOperationDto dto = new FileExecuteOperationDto();
        dto.setType("move");
        dto.setTargetParentId(folder.getId());
        OperationItemDto item = new OperationItemDto();
        item.setId(file.getId());
        item.setName(file.getName());
        dto.setItems(List.of(item));
        List<OperationResultVo> results = fileOperationService.move(dto, userId);
        assertEquals(1, results.size());
        assertEquals("success", results.get(0).getStatus());
    }

    private MultipartFile buildFile(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes());
    }

    private Path resolvePhysicalPath(UserWithSpace userWithSpace, String relativePath) {
        return userWithSpace.spacePath()
                .resolve(userWithSpace.user().getId().toString())
                .resolve("files")
                .resolve(relativePath);
    }

    private UserWithSpace prepareUserWithStorageSpace() {
        return prepareUserWithStorageSpace(10737418240L);
    }

    private UserWithSpace prepareUserWithStorageSpace(long quota) {
        Path spacePath = tempDir.resolve("space-" + System.nanoTime());
        StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
        spaceDto.setName("用户空间");
        spaceDto.setPath(spacePath.toString());
        spaceDto.setType("USER");
        spaceDto.setCapacity(Math.max(quota, 107374182400L));
        StorageSpaceVo space = storageSpaceService.save(spaceDto);
        systemConfigService.setValue("system.storage.space.id", String.valueOf(space.getId()));

        UserSaveDto userDto = new UserSaveDto();
        userDto.setUsername("downloadUser" + quota + "-" + System.nanoTime());
        userDto.setPassword("123456");
        UserVo user = userService.saveUser(userDto);

        UserStorageDto bindDto = new UserStorageDto();
        bindDto.setUserId(user.getId());
        bindDto.setStorageSpaceId(space.getId());
        bindDto.setQuota(quota);
        userService.bindStorageSpace(bindDto);

        return new UserWithSpace(user, spacePath);
    }

    private record UserWithSpace(UserVo user, Path spacePath) {
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = FileDownloadServiceImpl.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
