package com.fleyx.jcloud.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.dto.ChunkedUploadCompleteDto;
import com.fleyx.jcloud.model.dto.ChunkedUploadInitDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.vo.ChunkedUploadChunkVo;
import com.fleyx.jcloud.model.vo.ChunkedUploadInitVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private FileService fileService;

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
        dto.setParentId(FileNodeConstants.ROOT_ID);

        ChunkedUploadInitVo vo = chunkedUploadService.init(user.getId(), dto);

        assertNotNull(vo.getUploadId());
        assertEquals((int) CHUNK_SIZE, vo.getChunkSize());
        assertEquals(3, vo.getTotalChunks());
    }

    @Test
    void shouldCompleteChunkedUploadWhenQuotaIsZero() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace(0L);
        UserVo user = userWithSpace.user();

        long fileSize = CHUNK_SIZE;
        byte[] chunk = new byte[(int) fileSize];
        fillBytes(chunk, (byte) 13);
        String hash = DigestUtil.md5Hex(chunk);

        ChunkedUploadInitDto dto = new ChunkedUploadInitDto();
        dto.setFileName("unlimited.bin");
        dto.setSize(fileSize);
        dto.setParentId(FileNodeConstants.ROOT_ID);
        ChunkedUploadInitVo initVo = chunkedUploadService.init(user.getId(), dto);

        chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 0, buildChunk(chunk), hash);
        FileNodeVo vo = chunkedUploadService.complete(user.getId(), initVo.getUploadId(), null);

        assertNotNull(vo);
        assertEquals("unlimited.bin", vo.getName());
        assertEquals(fileSize, Long.parseLong(vo.getSize()));
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
        dto.setParentId(FileNodeConstants.ROOT_ID);
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
        dto.setParentId(FileNodeConstants.ROOT_ID);
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
        dto.setParentId(FileNodeConstants.ROOT_ID);
        ChunkedUploadInitVo initVo = chunkedUploadService.init(user.getId(), dto);

        chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 0,
                buildChunk(chunk0), DigestUtil.md5Hex(chunk0));
        chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 1,
                buildChunk(chunk1), DigestUtil.md5Hex(chunk1));

        FileNodeVo vo = chunkedUploadService.complete(user.getId(), initVo.getUploadId(), null);

        assertNotNull(vo.getId());
        assertEquals("chunked.bin", vo.getName());
        assertEquals("file", vo.getType());
        assertEquals(fileSize, Long.parseLong(vo.getSize()));

        Path targetPath = userWithSpace.spacePath()
                .resolve("files")
                .resolve(user.getUsername())
                .resolve("chunked.bin");
        assertTrue(Files.exists(targetPath));
        assertEquals(fileSize, Files.size(targetPath));
    }

    @Test
    void shouldCompleteUploadWhenParentIdIsZero() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();

        long fileSize = CHUNK_SIZE;
        byte[] chunk = new byte[(int) fileSize];
        fillBytes(chunk, (byte) 11);

        ChunkedUploadInitDto dto = new ChunkedUploadInitDto();
        dto.setFileName("zero-parent.bin");
        dto.setSize(fileSize);
        dto.setParentId("0");
        ChunkedUploadInitVo initVo = chunkedUploadService.init(user.getId(), dto);

        chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 0,
                buildChunk(chunk), DigestUtil.md5Hex(chunk));

        FileNodeVo vo = chunkedUploadService.complete(user.getId(), initVo.getUploadId(), null);

        assertNotNull(vo.getId());
        assertEquals("zero-parent.bin", vo.getName());
        assertEquals(FileNodeConstants.ROOT_ID, vo.getParentId());

        Path targetPath = userWithSpace.spacePath()
                .resolve("files")
                .resolve(user.getUsername())
                .resolve("zero-parent.bin");
        assertTrue(Files.exists(targetPath));
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
        dto.setParentId(FileNodeConstants.ROOT_ID);
        ChunkedUploadInitVo initVo = chunkedUploadService.init(user.getId(), dto);

        chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 0,
                buildChunk(chunk0), DigestUtil.md5Hex(chunk0));

        assertThrows(BusinessException.class,
                () -> chunkedUploadService.complete(user.getId(), initVo.getUploadId(), null));

        List<Integer> missingBefore = chunkedUploadService.listUploadedChunks(user.getId(), initVo.getUploadId());
        assertEquals(1, missingBefore.size());
        assertEquals(0, missingBefore.get(0));

        chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 1,
                buildChunk(chunk1), DigestUtil.md5Hex(chunk1));

        FileNodeVo vo = chunkedUploadService.complete(user.getId(), initVo.getUploadId(), null);
        assertEquals("chunked.bin", vo.getName());
        assertEquals(fileSize, Long.parseLong(vo.getSize()));
    }

    @Test
    void shouldReUploadExistingChunkWithoutDuplicateKeyError() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();

        long fileSize = CHUNK_SIZE;
        byte[] chunk = new byte[(int) CHUNK_SIZE];
        fillBytes(chunk, (byte) 3);
        String hash = DigestUtil.md5Hex(chunk);

        ChunkedUploadInitDto dto = new ChunkedUploadInitDto();
        dto.setFileName("reupload.bin");
        dto.setSize(fileSize);
        dto.setParentId(FileNodeConstants.ROOT_ID);
        ChunkedUploadInitVo initVo = chunkedUploadService.init(user.getId(), dto);

        chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 0, buildChunk(chunk), hash);
        ChunkedUploadChunkVo result = chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 0,
                buildChunk(chunk), hash);

        assertEquals(0, result.getChunkIndex());
        assertEquals("success", result.getStatus());

        FileNodeVo vo = chunkedUploadService.complete(user.getId(), initVo.getUploadId(), null);
        assertEquals("reupload.bin", vo.getName());
        assertEquals(fileSize, Long.parseLong(vo.getSize()));
    }

    @Test
    void shouldAutoRenameOnChunkedUploadConflict() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();

        fileService.upload(buildFile("chunked.bin", "existing"), user.getId(), FileNodeConstants.ROOT_ID, null);

        long fileSize = 15L * 1024 * 1024;
        byte[] chunk0 = new byte[(int) CHUNK_SIZE];
        byte[] chunk1 = new byte[(int) (fileSize - CHUNK_SIZE)];
        fillBytes(chunk0, (byte) 0);
        fillBytes(chunk1, (byte) 1);

        ChunkedUploadInitDto dto = new ChunkedUploadInitDto();
        dto.setFileName("chunked.bin");
        dto.setSize(fileSize);
        dto.setParentId(FileNodeConstants.ROOT_ID);
        ChunkedUploadInitVo initVo = chunkedUploadService.init(user.getId(), dto);

        chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 0,
                buildChunk(chunk0), DigestUtil.md5Hex(chunk0));
        chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 1,
                buildChunk(chunk1), DigestUtil.md5Hex(chunk1));

        ChunkedUploadCompleteDto completeDto = new ChunkedUploadCompleteDto();
        completeDto.setStrategy(ConflictStrategy.KEEP.getCode());
        FileNodeVo vo = chunkedUploadService.complete(user.getId(), initVo.getUploadId(), completeDto);

        assertNotNull(vo);
        assertEquals("chunked(1).bin", vo.getName());
    }

    @Test
    void shouldSkipOnChunkedUploadConflict() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();

        fileService.upload(buildFile("chunked.bin", "existing"), user.getId(), FileNodeConstants.ROOT_ID, null);

        long fileSize = CHUNK_SIZE;
        byte[] chunk = new byte[(int) CHUNK_SIZE];
        fillBytes(chunk, (byte) 5);

        ChunkedUploadInitDto dto = new ChunkedUploadInitDto();
        dto.setFileName("chunked.bin");
        dto.setSize(fileSize);
        dto.setParentId(FileNodeConstants.ROOT_ID);
        ChunkedUploadInitVo initVo = chunkedUploadService.init(user.getId(), dto);

        chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 0,
                buildChunk(chunk), DigestUtil.md5Hex(chunk));

        ChunkedUploadCompleteDto completeDto = new ChunkedUploadCompleteDto();
        completeDto.setStrategy(ConflictStrategy.SKIP.getCode());
        FileNodeVo vo = chunkedUploadService.complete(user.getId(), initVo.getUploadId(), completeDto);

        assertNull(vo);
    }

    @Test
    void shouldOverwriteOnChunkedUploadConflict() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();

        fileService.upload(buildFile("chunked.bin", "existing"), user.getId(), FileNodeConstants.ROOT_ID, null);

        long fileSize = CHUNK_SIZE;
        byte[] chunk = new byte[(int) CHUNK_SIZE];
        fillBytes(chunk, (byte) 9);

        ChunkedUploadInitDto dto = new ChunkedUploadInitDto();
        dto.setFileName("chunked.bin");
        dto.setSize(fileSize);
        dto.setParentId(FileNodeConstants.ROOT_ID);
        ChunkedUploadInitVo initVo = chunkedUploadService.init(user.getId(), dto);

        chunkedUploadService.uploadChunk(user.getId(), initVo.getUploadId(), 0,
                buildChunk(chunk), DigestUtil.md5Hex(chunk));

        ChunkedUploadCompleteDto completeDto = new ChunkedUploadCompleteDto();
        completeDto.setStrategy(ConflictStrategy.OVERWRITE.getCode());
        FileNodeVo vo = chunkedUploadService.complete(user.getId(), initVo.getUploadId(), completeDto);

        assertNotNull(vo);
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

    private MultipartFile buildFile(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes());
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
        userDto.setUsername("user_" + Long.toUnsignedString(System.nanoTime(), 36));
        userDto.setPassword("123456");
        userDto.setStorageSpaceId(space.getId());
        userDto.setQuota(toQuotaValue(quota));
        userDto.setQuotaUnit(toQuotaUnit(quota));
        UserVo user = userService.saveUser(userDto);
        UserContext.set(new CurrentUser(user.getId(), user.getUsername()));

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
}
