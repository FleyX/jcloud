package com.fleyx.jcloud.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.dto.ChunkedUploadInitDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.dto.UserStorageDto;
import com.fleyx.jcloud.model.vo.ChunkedUploadChunkVo;
import com.fleyx.jcloud.model.vo.ChunkedUploadInitVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分片上传服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChunkedUploadServiceTest {

    private static final long CHUNK_SIZE = 10L * 1024 * 1024;

    @Autowired
    private ChunkedUploadService chunkedUploadService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @TempDir
    Path tempDir;

    @Test
    void shouldInitChunkedUpload() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();

        ChunkedUploadInitDto dto = new ChunkedUploadInitDto();
        dto.setFileName("video.mp4");
        dto.setSize(25L * 1024 * 1024);
        dto.setParentId(0L);

        ChunkedUploadInitVo vo = chunkedUploadService.init(user.getId(), dto);

        assertNotNull(vo.getUploadId());
        assertEquals(CHUNK_SIZE, vo.getChunkSize());
        assertEquals(3, vo.getTotalChunks());
    }

    @Test
    void shouldUploadChunkAndListUploadedChunks() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();

        long fileSize = 15L * 1024 * 1024;
        byte[] chunk0 = new byte[(int) CHUNK_SIZE];
        byte[] chunk1 = new byte[(int) (fileSize - CHUNK_SIZE)];
        fillBytes(chunk0, (byte) 0);
        fillBytes(chunk1, (byte) 1);

        ChunkedUploadInitDto dto = new ChunkedUploadInitDto();
        dto.setFileName("chunked.bin");
        dto.setSize(fileSize);
        dto.setParentId(0L);
        ChunkedUploadInitVo initVo = chunkedUploadService.init(user.getId(), dto);

        String hash0 = DigestUtil.md5Hex(chunk0);
        String hash1 = DigestUtil.md5Hex(chunk1);

        ChunkedUploadChunkVo result0 = chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 0,
                buildChunk(chunk0), hash0);
        ChunkedUploadChunkVo result1 = chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 1,
                buildChunk(chunk1), hash1);

        assertEquals(0, result0.getChunkIndex());
        assertEquals("success", result0.getStatus());
        assertEquals(1, result1.getChunkIndex());
        assertEquals("success", result1.getStatus());

        List<Integer> uploaded = chunkedUploadService.listUploadedChunks(user.getId(), initVo.getUploadId());
        assertEquals(2, uploaded.size());
        assertTrue(uploaded.contains(0));
        assertTrue(uploaded.contains(1));
    }

    @Test
    void shouldRejectChunkWithWrongHash() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();

        ChunkedUploadInitDto dto = new ChunkedUploadInitDto();
        dto.setFileName("chunked.bin");
        dto.setSize(CHUNK_SIZE);
        dto.setParentId(0L);
        ChunkedUploadInitVo initVo = chunkedUploadService.init(user.getId(), dto);

        byte[] chunk = new byte[(int) CHUNK_SIZE];
        fillBytes(chunk, (byte) 7);

        assertThrows(BusinessException.class,
                () -> chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 0,
                        buildChunk(chunk), "wrong-hash"));
    }

    @Test
    void shouldCompleteUploadAndCreateFileNode() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();

        long fileSize = 15L * 1024 * 1024;
        byte[] chunk0 = new byte[(int) CHUNK_SIZE];
        byte[] chunk1 = new byte[(int) (fileSize - CHUNK_SIZE)];
        fillBytes(chunk0, (byte) 0);
        fillBytes(chunk1, (byte) 1);

        ChunkedUploadInitDto dto = new ChunkedUploadInitDto();
        dto.setFileName("chunked.bin");
        dto.setSize(fileSize);
        dto.setParentId(0L);
        ChunkedUploadInitVo initVo = chunkedUploadService.init(user.getId(), dto);

        chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 0,
                buildChunk(chunk0), DigestUtil.md5Hex(chunk0));
        chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 1,
                buildChunk(chunk1), DigestUtil.md5Hex(chunk1));

        FileNodeVo vo = chunkedUploadService.complete(user.getId(), initVo.getUploadId());

        assertNotNull(vo.getId());
        assertEquals("chunked.bin", vo.getName());
        assertEquals("file", vo.getType());
        assertEquals(fileSize, Long.parseLong(vo.getSize()));
        assertEquals("/", vo.getPathName());

        Path targetPath = userWithSpace.spacePath()
                .resolve(user.getId().toString())
                .resolve("files")
                .resolve("chunked.bin");
        assertTrue(Files.exists(targetPath));
        assertEquals(fileSize, Files.size(targetPath));
    }

    @Test
    void shouldResumeFromMissingChunks() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();

        long fileSize = 15L * 1024 * 1024;
        byte[] chunk0 = new byte[(int) CHUNK_SIZE];
        byte[] chunk1 = new byte[(int) (fileSize - CHUNK_SIZE)];
        fillBytes(chunk0, (byte) 0);
        fillBytes(chunk1, (byte) 1);

        ChunkedUploadInitDto dto = new ChunkedUploadInitDto();
        dto.setFileName("chunked.bin");
        dto.setSize(fileSize);
        dto.setParentId(0L);
        ChunkedUploadInitVo initVo = chunkedUploadService.init(user.getId(), dto);

        chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 0,
                buildChunk(chunk0), DigestUtil.md5Hex(chunk0));

        assertThrows(BusinessException.class,
                () -> chunkedUploadService.complete(user.getId(), initVo.getUploadId()));

        List<Integer> missingBefore = chunkedUploadService.listUploadedChunks(user.getId(), initVo.getUploadId());
        assertEquals(1, missingBefore.size());
        assertEquals(0, missingBefore.get(0));

        chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 1,
                buildChunk(chunk1), DigestUtil.md5Hex(chunk1));

        FileNodeVo vo = chunkedUploadService.complete(user.getId(), initVo.getUploadId());
        assertEquals("chunked.bin", vo.getName());
        assertEquals(fileSize, Long.parseLong(vo.getSize()));
    }

    private void fillBytes(byte[] bytes, byte value) {
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = value;
        }
    }

    private MultipartFile buildChunk(byte[] content) {
        return new MockMultipartFile("chunk", "chunk.bin", "application/octet-stream", content);
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
        userDto.setUsername("chunkUser" + quota + "-" + System.nanoTime());
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
}
