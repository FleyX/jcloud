package com.fleyx.jcloud.service.support;

import cn.hutool.crypto.digest.DigestUtil;
import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.config.UploadProperties;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.ChunkedUploadInitDto;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.dto.FileInstantUploadDto;
import com.fleyx.jcloud.model.dto.OperationItemDto;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.BatchChunkedUploadInitItemVo;
import com.fleyx.jcloud.model.vo.ChunkedUploadInitVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.OperationResultVo;
import com.fleyx.jcloud.service.ChunkedUploadService;
import com.fleyx.jcloud.service.FileOperationService;
import com.fleyx.jcloud.service.FileService;
import com.fleyx.jcloud.service.WebDavService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 02 票：文件核心写路径集中记账集成测试。
 * <p>
 * 覆盖普通上传、秒传、覆盖上传、分片上传合并、文件面板复制（含递归累计与配额复检）、
 * WebDAV 上传/覆盖/复制/删除。每个操作后断言 {@code t_user.used_space} 与逻辑口径重算值一致
 * 且等于预期增量。
 */
@SpringBootTest(properties = "jcloud.upload.chunk-size=10485760")
@Transactional
class UsedSpaceWritePathAccountingTest extends IntegrationTestBase {

    @Autowired
    private FileService fileService;

    @Autowired
    private FileOperationService fileOperationService;

    @Autowired
    private ChunkedUploadService chunkedUploadService;

    @Autowired
    private WebDavService webDavService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserUsedSpaceSupport userUsedSpaceSupport;

    @Autowired
    private UploadProperties uploadProperties;

    // ---------- 普通上传 ----------

    @Test
    void shouldAccountNormalUpload() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        String userId = userWithSpace.user().getId();
        byte[] content = "normal upload content".getBytes(StandardCharsets.UTF_8);

        fileService.upload(buildFile("a.txt", content), userId, FileNodeConstants.ROOT_ID, null);

        assertUsedEqualsRecalc(userId, content.length);
    }

    // ---------- 秒传 ----------

    @Test
    void shouldAccountInstantUpload() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        String userId = userWithSpace.user().getId();
        String content = "instant upload content";
        FileNodeVo source = fileService.upload(buildFile("src.txt", content), userId, FileNodeConstants.ROOT_ID, null);

        FileInstantUploadDto dto = new FileInstantUploadDto();
        dto.setCandidateId(source.getId());
        dto.setFullHash(DigestUtil.md5Hex(content.getBytes(StandardCharsets.UTF_8)));
        dto.setFileName("copy.txt");
        dto.setParentId(FileNodeConstants.ROOT_ID);
        FileNodeVo instant = fileService.instantUpload(dto, userId);

        assertNotNull(instant);
        // 秒传按逻辑口径累加完整文件大小（ADR-0027）：源文件与副本各计一次
        assertUsedEqualsRecalc(userId, content.getBytes(StandardCharsets.UTF_8).length * 2L);
    }

    // ---------- 覆盖上传 ----------

    @Test
    void shouldAccountOverwriteUpload() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        String userId = userWithSpace.user().getId();
        byte[] oldContent = "12345".getBytes(StandardCharsets.UTF_8);
        byte[] newContent = "1234567890".getBytes(StandardCharsets.UTF_8);
        fileService.upload(buildFile("same.txt", oldContent), userId, FileNodeConstants.ROOT_ID, null);
        assertEquals(oldContent.length, usedSpaceOf(userId));

        fileService.upload(buildFile("same.txt", newContent), userId, FileNodeConstants.ROOT_ID,
                ConflictStrategy.OVERWRITE.getCode());

        // 覆盖处理器原子扣减旧文件，末尾只加新文件大小
        assertUsedEqualsRecalc(userId, newContent.length);
    }

    // ---------- 分片上传合并 ----------

    @Test
    void shouldAccountChunkedUploadComplete() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        String userId = userWithSpace.user().getId();

        long fileSize = 15L * 1024 * 1024;
        byte[] chunk0 = new byte[(int) uploadProperties.getChunkSize()];
        byte[] chunk1 = new byte[(int) (fileSize - uploadProperties.getChunkSize())];
        fillBytes(chunk0, (byte) 0);
        fillBytes(chunk1, (byte) 1);

        ChunkedUploadInitDto dto = new ChunkedUploadInitDto();
        dto.setFileName("chunked.bin");
        dto.setSize(fileSize);
        dto.setParentId(FileNodeConstants.ROOT_ID);
        ChunkedUploadInitVo initVo = initSingle(dto, userId);

        chunkedUploadService.uploadChunk(userId, initVo.getUploadId(), 0,
                buildChunk(chunk0), DigestUtil.md5Hex(chunk0));
        chunkedUploadService.uploadChunk(userId, initVo.getUploadId(), 1,
                buildChunk(chunk1), DigestUtil.md5Hex(chunk1));

        FileNodeVo vo = chunkedUploadService.complete(userId, initVo.getUploadId(), null);

        assertNotNull(vo);
        assertUsedEqualsRecalc(userId, fileSize);
    }

    // ---------- 文件面板复制 ----------

    @Test
    void shouldAccountFolderCopyRecursively() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        String userId = userWithSpace.user().getId();
        byte[] content = "folder copy".getBytes(StandardCharsets.UTF_8);
        FileNodeVo docs = createFolder(userId, "docs", FileNodeConstants.ROOT_ID);
        fileService.upload(buildFile("a.txt", content), userId, docs.getId(), null);
        fileService.upload(buildFile("b.txt", content), userId, docs.getId(), null);
        long before = usedSpaceOf(userId);
        assertEquals(content.length * 2L, before);

        FileNodeVo backup = createFolder(userId, "backup", FileNodeConstants.ROOT_ID);
        List<OperationResultVo> results = fileOperationService.copy(
                buildCopyDto(backup.getId(), docs), userId);

        assertEquals("success", results.get(0).getStatus());
        // 递归复制每个文件各累计一次
        assertUsedEqualsRecalc(userId, before + content.length * 2L);
    }

    @Test
    void shouldRejectFolderCopyWhenQuotaExceededByFreshRecheck() throws Exception {
        // 配额仅能容纳 3 个文件：上传 2 个后递归复制第 1 个文件消耗第 3 个名额，
        // 第 2 个文件必须按 DB 最新值复检并拒绝（实体快照无法发现超额）
        byte[] content = "abcde".getBytes(StandardCharsets.UTF_8);
        long quota = content.length * 3L;
        UserWithSpace userWithSpace = prepareUserWithStorageSpace(quota);
        String userId = userWithSpace.user().getId();
        FileNodeVo docs = createFolder(userId, "docs", FileNodeConstants.ROOT_ID);
        fileService.upload(buildFile("a.txt", content), userId, docs.getId(), null);
        fileService.upload(buildFile("b.txt", content), userId, docs.getId(), null);
        assertEquals(content.length * 2L, usedSpaceOf(userId));

        FileNodeVo backup = createFolder(userId, "backup", FileNodeConstants.ROOT_ID);

        assertThrows(BusinessException.class, () -> fileOperationService.copy(
                buildCopyDto(backup.getId(), docs), userId));
    }

    // ---------- WebDAV ----------

    @Test
    void shouldAccountWebDavUploadNewFile() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        String userId = userWithSpace.user().getId();
        String username = userWithSpace.user().getUsername();
        UserContext.set(new CurrentUser(userId, username));
        try {
            byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
            put(username, "/new.txt", content);

            assertUsedEqualsRecalc(userId, content.length);
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void shouldAccountWebDavPutOverwrite() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        String userId = userWithSpace.user().getId();
        String username = userWithSpace.user().getUsername();
        UserContext.set(new CurrentUser(userId, username));
        try {
            byte[] oldContent = "12345".getBytes(StandardCharsets.UTF_8);
            byte[] newContent = "1234567890".getBytes(StandardCharsets.UTF_8);
            put(username, "/over.txt", oldContent);
            assertEquals(oldContent.length, usedSpaceOf(userId));

            put(username, "/over.txt", newContent);

            // WebDAV PUT 覆盖为原地更新节点（票据01 ab1b23a）：复用原 FileNode id，
            // 已用空间按差额调整，无旧节点残留，记账值与重算值一致且等于新大小
            assertUsedEqualsRecalc(userId, newContent.length);
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void shouldAccountWebDavCopy() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        String userId = userWithSpace.user().getId();
        String username = userWithSpace.user().getUsername();
        UserContext.set(new CurrentUser(userId, username));
        try {
            byte[] content = "data".getBytes(StandardCharsets.UTF_8);
            put(username, "/a.txt", content);
            copy(username, "/a.txt", "/b.txt");

            assertUsedEqualsRecalc(userId, content.length * 2L);
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void shouldAccountWebDavDelete() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        String userId = userWithSpace.user().getId();
        String username = userWithSpace.user().getUsername();
        UserContext.set(new CurrentUser(userId, username));
        try {
            byte[] content = "x".getBytes(StandardCharsets.UTF_8);
            put(username, "/del.txt", content);
            assertEquals(content.length, usedSpaceOf(userId));

            delete(username, "/del.txt");

            assertUsedEqualsRecalc(userId, 0L);
        } finally {
            UserContext.clear();
        }
    }

    // ---------- 工具方法 ----------

    private long usedSpaceOf(String userId) {
        User user = userMapper.selectById(userId);
        return user == null || user.getUsedSpace() == null ? 0L : user.getUsedSpace();
    }

    /**
     * 断言记账值等于重算值与预期增量。先读记账值再重算，避免重算覆盖掩盖记账错误。
     */
    private void assertUsedEqualsRecalc(String userId, long expected) {
        long used = usedSpaceOf(userId);
        long recalc = userUsedSpaceSupport.recalcUsedSpace(userId);
        assertEquals(expected, used, "used_space 记账值");
        assertEquals(expected, recalc, "重算值");
    }

    private MultipartFile buildFile(String name, byte[] content) {
        return new MockMultipartFile("file", name, "text/plain", content);
    }

    private MultipartFile buildFile(String name, String content) {
        return buildFile(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private FileNodeVo createFolder(String userId, String name, String parentId) {
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(parentId);
        dto.setName(name);
        return fileOperationService.createFolder(dto, userId);
    }

    private FileExecuteOperationDto buildCopyDto(String targetParentId, FileNodeVo source) {
        FileExecuteOperationDto dto = new FileExecuteOperationDto();
        dto.setType("copy");
        dto.setTargetParentId(targetParentId);
        OperationItemDto item = new OperationItemDto();
        item.setId(source.getId());
        item.setName(source.getName());
        item.setStrategy(ConflictStrategy.OVERWRITE.getCode());
        dto.setItems(List.of(item));
        return dto;
    }

    private ChunkedUploadInitVo initSingle(ChunkedUploadInitDto dto, String userId) {
        dto.setClientFileId("client-" + System.nanoTime());
        List<BatchChunkedUploadInitItemVo> result = chunkedUploadService.init(userId, List.of(dto));
        assertEquals(1, result.size());
        assertEquals("success", result.get(0).getStatus());
        return result.get(0).getData();
    }

    private MultipartFile buildChunk(byte[] content) {
        return new MockMultipartFile("chunk", "chunk.bin", "application/octet-stream", content);
    }

    private void fillBytes(byte[] bytes, byte value) {
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = value;
        }
    }

    private void put(String username, String path, byte[] content) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/dav/" + username + path);
        request.setContent(content);
        MockHttpServletResponse response = new MockHttpServletResponse();
        webDavService.handle(username, request, response);
        assertEquals(201, response.getStatus());
    }

    private void copy(String username, String sourcePath, String targetPath) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("COPY", "/dav/" + username + sourcePath);
        request.addHeader("Destination", "http://localhost:8080/dav/" + username + targetPath);
        MockHttpServletResponse response = new MockHttpServletResponse();
        webDavService.handle(username, request, response);
        assertEquals(201, response.getStatus());
    }

    private void delete(String username, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/dav/" + username + path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        webDavService.handle(username, request, response);
        assertEquals(204, response.getStatus());
    }
}
