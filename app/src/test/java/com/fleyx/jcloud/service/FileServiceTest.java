package com.fleyx.jcloud.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.dto.FileInstantUploadDto;
import com.fleyx.jcloud.model.dto.FilePageQueryDto;
import com.fleyx.jcloud.model.dto.FileUploadPreCheckDto;
import com.fleyx.jcloud.model.dto.OperationItemDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.vo.ConflictItemVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UploadPreCheckVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.util.FileHashUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FileServiceTest {

    @Autowired
    private FileService fileService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private FileOperationService fileOperationService;

    @TempDir
    Path tempDir;

    @Test
    void shouldUploadFileToRoot() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        MultipartFile file = new MockMultipartFile(
                "file",
                "hello.txt",
                "text/plain",
                "Hello, jcloud!".getBytes()
        );

        FileNodeVo vo = fileService.upload(file, user.getId(), 0L, null);

        assertNotNull(vo.getId());
        assertEquals("hello.txt", vo.getName());
        assertEquals("file", vo.getType());
        assertEquals(14L, Long.parseLong(vo.getSize()));
        assertEquals("/hello.txt", vo.getPathName());
        assertTrue(Files.exists(resolvePhysicalPath(userWithSpace, vo.getPhysicalPath())));
    }

    @Test
    void shouldRejectUploadWhenQuotaExceeded() {
        UserVo user = prepareUserWithStorageSpace(10L).user();
        MultipartFile file = new MockMultipartFile(
                "file",
                "large.txt",
                "text/plain",
                "This file exceeds the tiny quota".getBytes()
        );

        assertThrows(BusinessException.class, () -> fileService.upload(file, user.getId(), 0L, null));
    }

    @Test
    void shouldPreCheckUploadConflict() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        fileService.upload(buildFile("hello.txt", "hello"), user.getId(), 0L, null);

        FileUploadPreCheckDto dto = new FileUploadPreCheckDto();
        dto.setFileName("hello.txt");
        dto.setSize(5L);
        dto.setParentId(0L);

        UploadPreCheckVo result = fileService.preCheckUpload(dto, user.getId());

        assertEquals(1, result.getConflicts().size());
        assertEquals("hello.txt", result.getConflicts().get(0).getSourceName());
        assertEquals("hello.txt", result.getConflicts().get(0).getExistingName());
        assertEquals("file", result.getConflicts().get(0).getExistingType());
        assertTrue(result.getCandidates().isEmpty());
    }

    @Test
    void shouldRejectUploadWhenNameConflictsWithoutStrategy() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        fileService.upload(buildFile("hello.txt", "hello"), user.getId(), 0L, null);

        MultipartFile file = buildFile("hello.txt", "world");

        assertThrows(BusinessException.class, () -> fileService.upload(file, user.getId(), 0L, null));
    }

    @Test
    void shouldSkipUploadWhenNameConflicts() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo existing = fileService.upload(buildFile("hello.txt", "hello"), user.getId(), 0L, null);

        FileNodeVo skipped = fileService.upload(buildFile("hello.txt", "world"), user.getId(), 0L,
                ConflictStrategy.SKIP.getCode());

        assertNull(skipped);
        FileNodeVo current = fileService.download(existing.getId(), user.getId()) != null ? existing : null;
        assertNotNull(current);
    }

    @Test
    void shouldOverwriteUploadWhenNameConflicts() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        fileService.upload(buildFile("hello.txt", "hello"), user.getId(), 0L, null);

        FileNodeVo overwritten = fileService.upload(buildFile("hello.txt", "world"), user.getId(), 0L,
                ConflictStrategy.OVERWRITE.getCode());

        assertNotNull(overwritten);
        assertEquals("hello.txt", overwritten.getName());
        assertEquals(5L, Long.parseLong(overwritten.getSize()));
    }

    @Test
    void shouldAutoRenameUploadWhenNameConflicts() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        fileService.upload(buildFile("hello.txt", "hello"), user.getId(), 0L, null);

        FileNodeVo renamed = fileService.upload(buildFile("hello.txt", "world"), user.getId(), 0L,
                ConflictStrategy.KEEP.getCode());

        assertNotNull(renamed);
        assertEquals("hello(1).txt", renamed.getName());
    }

    @Test
    void shouldListRootFiles() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        fileService.upload(buildFile("a.txt", "A"), user.getId(), 0L, null);
        fileService.upload(buildFile("b.txt", "B"), user.getId(), 0L, null);

        FilePageQueryDto query = new FilePageQueryDto();
        query.setParentId(0L);
        query.setPageNum(1L);
        query.setPageSize(10L);

        IPage<FileNodeVo> page = fileService.list(query, user.getId());

        assertEquals(2L, page.getTotal());
        assertEquals(2, page.getRecords().size());
    }

    @Test
    void shouldSearchFilesByNameAcrossAllFolders() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        fileService.upload(buildFile("annual-report.pdf", "report"), user.getId(), 0L, null);
        FileNodeVo folder = createFolder(user.getId(), "docs");
        FileNodeVo fileInRoot = fileService.upload(buildFile("report-summary.txt", "summary"), user.getId(), 0L, null);
        moveFileToFolder(user.getId(), fileInRoot.getId(), folder.getId());

        FilePageQueryDto query = new FilePageQueryDto();
        query.setName("report");
        query.setPageNum(1L);
        query.setPageSize(10L);

        IPage<FileNodeVo> page = fileService.list(query, user.getId());

        List<String> names = page.getRecords().stream().map(FileNodeVo::getName).toList();
        assertEquals(2, names.size());
        assertTrue(names.contains("annual-report.pdf"));
        assertTrue(names.contains("report-summary.txt"));
    }

    @Test
    void shouldSearchFilesWithExactNameMatch() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        fileService.upload(buildFile("hello.txt", "hello"), user.getId(), 0L, null);
        fileService.upload(buildFile("world.txt", "world"), user.getId(), 0L, null);

        FilePageQueryDto query = new FilePageQueryDto();
        query.setName("hello.txt");
        query.setPageNum(1L);
        query.setPageSize(10L);

        IPage<FileNodeVo> page = fileService.list(query, user.getId());

        assertEquals(1L, page.getTotal());
        assertEquals("hello.txt", page.getRecords().get(0).getName());
    }

    @Test
    void shouldSearchFilesWithFuzzyNameMatch() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        fileService.upload(buildFile("hello-world.txt", "hello"), user.getId(), 0L, null);

        FilePageQueryDto query = new FilePageQueryDto();
        query.setName("hello-wrld");
        query.setPageNum(1L);
        query.setPageSize(10L);

        IPage<FileNodeVo> page = fileService.list(query, user.getId());

        assertEquals(1L, page.getTotal());
        assertEquals("hello-world.txt", page.getRecords().get(0).getName());
    }

    @Test
    void shouldReturnEmptyWhenNoFileMatches() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        fileService.upload(buildFile("hello.txt", "hello"), user.getId(), 0L, null);

        FilePageQueryDto query = new FilePageQueryDto();
        query.setName("nonexistent");
        query.setPageNum(1L);
        query.setPageSize(10L);

        IPage<FileNodeVo> page = fileService.list(query, user.getId());

        assertEquals(0L, page.getTotal());
        assertTrue(page.getRecords().isEmpty());
    }

    @Test
    void shouldHandleSpecialCharactersInSearchKeyword() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        fileService.upload(buildFile("report_2026.pdf", "report"), user.getId(), 0L, null);
        fileService.upload(buildFile("report 100%.txt", "percent"), user.getId(), 0L, null);

        FilePageQueryDto query = new FilePageQueryDto();
        query.setName("100%");
        query.setPageNum(1L);
        query.setPageSize(10L);

        IPage<FileNodeVo> page = fileService.list(query, user.getId());

        assertEquals(1L, page.getTotal());
        assertEquals("report 100%.txt", page.getRecords().get(0).getName());
    }

    @Test
    void shouldSortSearchResultsWithFoldersFirstThenByTimeDesc() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo olderFile = fileService.upload(buildFile("report-old.txt", "old"), user.getId(), 0L, null);
        FileNodeVo folder = createFolder(user.getId(), "report-folder");
        FileNodeVo newerFile = fileService.upload(buildFile("report-new.txt", "new"), user.getId(), 0L, null);

        FilePageQueryDto query = new FilePageQueryDto();
        query.setName("report");
        query.setPageNum(1L);
        query.setPageSize(10L);

        IPage<FileNodeVo> page = fileService.list(query, user.getId());

        List<String> names = page.getRecords().stream().map(FileNodeVo::getName).toList();
        assertEquals(3, names.size());
        assertEquals("report-folder", names.get(0));
        assertEquals("report-new.txt", names.get(1));
        assertEquals("report-old.txt", names.get(2));
    }

    @Test
    void shouldDownloadUploadedFile() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo uploaded = fileService.upload(buildFile("download.txt", "Download me"), user.getId(), 0L, null);

        FileDownloadResult result = fileService.download(uploaded.getId(), user.getId());

        assertEquals("download.txt", result.getFileName());
        try (InputStream is = result.getInputStream()) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("Download me", content);
        }
    }

    @Test
    void shouldInstantUploadSameFileForSameUser() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        String content = "Hello, instant upload!";
        FileNodeVo uploaded = fileService.upload(buildFile("hello.txt", content), user.getId(), 0L, null);
        String fullHash = DigestUtil.md5Hex(content.getBytes(StandardCharsets.UTF_8));

        FileUploadPreCheckDto preCheckDto = new FileUploadPreCheckDto();
        preCheckDto.setFileName("hello-copy.txt");
        preCheckDto.setSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        preCheckDto.setPartialHash(fullHash);

        UploadPreCheckVo preCheckResult = fileService.preCheckUpload(preCheckDto, user.getId());

        assertEquals(1, preCheckResult.getCandidates().size());
        assertEquals(uploaded.getId(), preCheckResult.getCandidates().get(0).getId());
        assertEquals(fullHash, preCheckResult.getCandidates().get(0).getHash());

        FileInstantUploadDto instantDto = new FileInstantUploadDto();
        instantDto.setCandidateId(preCheckResult.getCandidates().get(0).getId());
        instantDto.setFullHash(fullHash);
        instantDto.setFileName("hello-copy.txt");
        instantDto.setParentId(0L);

        FileNodeVo instant = fileService.instantUpload(instantDto, user.getId());

        assertNotNull(instant.getId());
        assertEquals("hello-copy.txt", instant.getName());
        assertEquals(uploaded.getSize(), instant.getSize());
        assertEquals(fullHash, instant.getHash());

        Path originalPath = resolvePhysicalPath(userWithSpace, uploaded.getPhysicalPath());
        Path copyPath = resolvePhysicalPath(userWithSpace, instant.getPhysicalPath());
        assertTrue(Files.exists(originalPath));
        assertTrue(Files.exists(copyPath));
        assertEquals(Files.size(originalPath), Files.size(copyPath));
    }

    @Test
    void shouldNotReturnCandidatesFromOtherUsers() throws Exception {
        UserVo userA = prepareUserWithStorageSpace().user();
        UserVo userB = prepareUserWithStorageSpace().user();
        String content = "Cross-user data";
        fileService.upload(buildFile("hello.txt", content), userA.getId(), 0L, null);
        String fullHash = DigestUtil.md5Hex(content.getBytes(StandardCharsets.UTF_8));

        FileUploadPreCheckDto preCheckDto = new FileUploadPreCheckDto();
        preCheckDto.setFileName("hello.txt");
        preCheckDto.setSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        preCheckDto.setPartialHash(fullHash);

        UploadPreCheckVo preCheckResult = fileService.preCheckUpload(preCheckDto, userB.getId());

        assertTrue(preCheckResult.getCandidates().isEmpty());
    }

    @Test
    void shouldRejectInstantUploadWhenQuotaExceeded() throws Exception {
        String content = "Quota check";
        long quota = content.getBytes(StandardCharsets.UTF_8).length + 1L;
        UserVo user = prepareUserWithStorageSpace(quota).user();
        FileNodeVo uploaded = fileService.upload(buildFile("hello.txt", content), user.getId(), 0L, null);

        FileInstantUploadDto dto = new FileInstantUploadDto();
        dto.setCandidateId(uploaded.getId());
        dto.setFullHash(DigestUtil.md5Hex(content.getBytes(StandardCharsets.UTF_8)));
        dto.setFileName("hello-copy.txt");
        dto.setParentId(0L);

        assertThrows(BusinessException.class, () -> fileService.instantUpload(dto, user.getId()));
    }

    @Test
    void shouldRejectInstantUploadWhenFullHashMismatch() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo uploaded = fileService.upload(buildFile("hello.txt", "Hello"), user.getId(), 0L, null);

        FileInstantUploadDto dto = new FileInstantUploadDto();
        dto.setCandidateId(uploaded.getId());
        dto.setFullHash("mismatched-full-hash");
        dto.setFileName("hello-copy.txt");
        dto.setParentId(0L);

        assertThrows(BusinessException.class, () -> fileService.instantUpload(dto, user.getId()));
    }

    @Test
    void shouldPreCheckLargeFileBySampleHash() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        Path largeFile = createLargeFile(150L * 1024 * 1024 + 1);

        FileNodeVo uploaded = fileService.upload(new PathMultipartFile(largeFile, "large.bin"), user.getId(), 0L, null);
        String sampleHash = FileHashUtil.identityHash(largeFile);
        String fullHash = FileHashUtil.fullHash(largeFile);

        assertEquals(sampleHash, uploaded.getHash());

        FileUploadPreCheckDto preCheckDto = new FileUploadPreCheckDto();
        preCheckDto.setFileName("large-copy.bin");
        preCheckDto.setSize(Files.size(largeFile));
        preCheckDto.setPartialHash(sampleHash);

        UploadPreCheckVo preCheckResult = fileService.preCheckUpload(preCheckDto, user.getId());

        assertEquals(1, preCheckResult.getCandidates().size());
        assertEquals(uploaded.getId(), preCheckResult.getCandidates().get(0).getId());

        FileInstantUploadDto instantDto = new FileInstantUploadDto();
        instantDto.setCandidateId(preCheckResult.getCandidates().get(0).getId());
        instantDto.setFullHash(fullHash);
        instantDto.setFileName("large-copy.bin");
        instantDto.setParentId(0L);

        FileNodeVo instant = fileService.instantUpload(instantDto, user.getId());

        assertNotNull(instant.getId());
        assertEquals("large-copy.bin", instant.getName());
        assertEquals(uploaded.getSize(), instant.getSize());
    }

    private Path createLargeFile(long size) throws IOException {
        Path file = tempDir.resolve("large-" + System.nanoTime() + ".bin");
        byte[] buffer = new byte[8192];
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = (byte) (i % 256);
        }
        try (java.io.OutputStream os = Files.newOutputStream(file)) {
            long remaining = size;
            while (remaining > 0) {
                int write = (int) Math.min(buffer.length, remaining);
                os.write(buffer, 0, write);
                remaining -= write;
            }
        }
        return file;
    }

    private MultipartFile buildFile(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes());
    }

    private FileNodeVo createFolder(Long userId, String name) {
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(0L);
        dto.setName(name);
        return fileOperationService.createFolder(dto, userId);
    }

    private void moveFileToFolder(Long userId, Long fileId, Long folderId) {
        FileExecuteOperationDto dto = new FileExecuteOperationDto();
        dto.setType("move");
        dto.setTargetParentId(folderId);
        OperationItemDto item = new OperationItemDto();
        item.setId(fileId);
        dto.setItems(List.of(item));
        fileOperationService.move(dto, userId);
    }

    private Path resolvePhysicalPath(UserWithSpace userWithSpace, String physicalPath) {
        return userWithSpace.spacePath()
                .resolve(userWithSpace.user().getId().toString())
                .resolve("files")
                .resolve(physicalPath);
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
        StorageSpaceVo space = storageSpaceService.save(spaceDto);

        UserSaveDto userDto = new UserSaveDto();
        userDto.setUsername("fileUser" + quota + "-" + System.nanoTime());
        userDto.setPassword("123456");
        userDto.setStorageSpaceId(space.getId());
        userDto.setQuota(toQuotaValue(quota));
        userDto.setQuotaUnit(toQuotaUnit(quota));
        UserVo user = userService.saveUser(userDto);

        return new UserWithSpace(user, spacePath);
    }

    private static long toQuotaValue(long quotaBytes) {
        return quotaBytes == 10737418240L ? 10L : quotaBytes;
    }

    private static String toQuotaUnit(long quotaBytes) {
        return quotaBytes == 10737418240L ? "GB" : "B";
    }

    private record UserWithSpace(UserVo user, Path spacePath) {
    }

    /**
     * 基于本地文件的多部分文件包装，避免大文件测试时把整个文件载入内存。
     */
    private static class PathMultipartFile implements MultipartFile {

        private final Path path;
        private final String originalFilename;

        PathMultipartFile(Path path, String originalFilename) {
            this.path = path;
            this.originalFilename = originalFilename;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return "application/octet-stream";
        }

        @Override
        public boolean isEmpty() {
            try {
                return Files.size(path) == 0;
            } catch (IOException e) {
                return true;
            }
        }

        @Override
        public long getSize() {
            try {
                return Files.size(path);
            } catch (IOException e) {
                return 0;
            }
        }

        @Override
        public byte[] getBytes() throws IOException {
            return Files.readAllBytes(path);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return Files.newInputStream(path);
        }

        @Override
        public void transferTo(File dest) throws IOException, IllegalStateException {
            Files.copy(path, dest.toPath());
        }
    }
}
