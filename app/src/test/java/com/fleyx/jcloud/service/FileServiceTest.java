package com.fleyx.jcloud.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.dto.FileInstantUploadDto;
import com.fleyx.jcloud.model.dto.FilePageQueryDto;
import com.fleyx.jcloud.model.dto.FilePreCheckDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.dto.UserStorageDto;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
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

        FileNodeVo vo = fileService.upload(file, user.getId());

        assertNotNull(vo.getId());
        assertEquals("hello.txt", vo.getName());
        assertEquals("file", vo.getType());
        assertEquals(14L, Long.parseLong(vo.getSize()));
        assertEquals("/", vo.getPathName());
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

        assertThrows(BusinessException.class, () -> fileService.upload(file, user.getId()));
    }

    @Test
    void shouldListRootFiles() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        fileService.upload(buildFile("a.txt", "A"), user.getId());
        fileService.upload(buildFile("b.txt", "B"), user.getId());

        FilePageQueryDto query = new FilePageQueryDto();
        query.setParentId(0L);
        query.setPageNum(1L);
        query.setPageSize(10L);

        IPage<FileNodeVo> page = fileService.list(query, user.getId());

        assertEquals(2L, page.getTotal());
        assertEquals(2, page.getRecords().size());
    }

    @Test
    void shouldDownloadUploadedFile() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo uploaded = fileService.upload(buildFile("download.txt", "Download me"), user.getId());

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
        FileNodeVo uploaded = fileService.upload(buildFile("hello.txt", content), user.getId());
        String fullHash = DigestUtil.md5Hex(content.getBytes(StandardCharsets.UTF_8));

        FilePreCheckDto preCheckDto = new FilePreCheckDto();
        preCheckDto.setFileName("hello-copy.txt");
        preCheckDto.setSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        preCheckDto.setPartialHash(fullHash);

        List<FileNodeVo> candidates = fileService.preCheck(preCheckDto, user.getId());

        assertEquals(1, candidates.size());
        assertEquals(uploaded.getId(), candidates.get(0).getId());
        assertEquals(fullHash, candidates.get(0).getHash());

        FileInstantUploadDto instantDto = new FileInstantUploadDto();
        instantDto.setCandidateId(candidates.get(0).getId());
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
        fileService.upload(buildFile("hello.txt", content), userA.getId());
        String fullHash = DigestUtil.md5Hex(content.getBytes(StandardCharsets.UTF_8));

        FilePreCheckDto preCheckDto = new FilePreCheckDto();
        preCheckDto.setFileName("hello.txt");
        preCheckDto.setSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        preCheckDto.setPartialHash(fullHash);

        List<FileNodeVo> candidates = fileService.preCheck(preCheckDto, userB.getId());

        assertTrue(candidates.isEmpty());
    }

    @Test
    void shouldRejectInstantUploadWhenQuotaExceeded() throws Exception {
        String content = "Quota check";
        long quota = content.getBytes(StandardCharsets.UTF_8).length + 1L;
        UserVo user = prepareUserWithStorageSpace(quota).user();
        FileNodeVo uploaded = fileService.upload(buildFile("hello.txt", content), user.getId());

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
        FileNodeVo uploaded = fileService.upload(buildFile("hello.txt", "Hello"), user.getId());

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

        FileNodeVo uploaded = fileService.upload(new PathMultipartFile(largeFile, "large.bin"), user.getId());
        String sampleHash = FileHashUtil.identityHash(largeFile);
        String fullHash = FileHashUtil.fullHash(largeFile);

        assertEquals(sampleHash, uploaded.getHash());

        FilePreCheckDto preCheckDto = new FilePreCheckDto();
        preCheckDto.setFileName("large-copy.bin");
        preCheckDto.setSize(Files.size(largeFile));
        preCheckDto.setPartialHash(sampleHash);

        List<FileNodeVo> candidates = fileService.preCheck(preCheckDto, user.getId());

        assertEquals(1, candidates.size());
        assertEquals(uploaded.getId(), candidates.get(0).getId());

        FileInstantUploadDto instantDto = new FileInstantUploadDto();
        instantDto.setCandidateId(candidates.get(0).getId());
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
        spaceDto.setCapacity(Math.max(quota, 107374182400L));
        StorageSpaceVo space = storageSpaceService.save(spaceDto);

        UserSaveDto userDto = new UserSaveDto();
        userDto.setUsername("fileUser" + quota + "-" + System.nanoTime());
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
