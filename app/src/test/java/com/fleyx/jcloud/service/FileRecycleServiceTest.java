package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.RecycleRecordMapper;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileDeleteDto;
import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.dto.FileExecuteRestoreDto;
import com.fleyx.jcloud.model.dto.FilePermanentDeleteDto;
import com.fleyx.jcloud.model.dto.FilePageQueryDto;
import com.fleyx.jcloud.model.dto.FilePreCheckRestoreDto;
import com.fleyx.jcloud.model.dto.OperationItemDto;
import com.fleyx.jcloud.model.dto.RestoreItemDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.vo.ConflictItemVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.OperationResultVo;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收站服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FileRecycleServiceTest {

    @Autowired
    private FileRecycleService fileRecycleService;

    @Autowired
    private FileService fileService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private RecycleRecordMapper recycleRecordMapper;

    @Autowired
    private FileOperationService fileOperationService;

    @TempDir
    Path tempDir;

    @Test
    void shouldMoveSingleFileToTrash() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = fileService.upload(buildFile("hello.txt", "Hello"), user.getId(), 0L, null);
        Path originalPath = resolvePhysicalPath(userWithSpace, "hello.txt");
        assertTrue(Files.exists(originalPath));

        FileDeleteDto dto = new FileDeleteDto();
        dto.setIds(List.of(file.getId()));

        List<OperationResultVo> results = fileRecycleService.deleteToTrash(dto, user.getId());

        assertEquals(1, results.size());
        assertEquals("success", results.get(0).getStatus());

        // 原 FileNode 被物理删除
        assertNull(fileMapper.selectById(file.getId()));

        // 回收站记录生成
        List<RecycleRecord> records = recycleRecordMapper.selectList(
                new LambdaQueryWrapper<RecycleRecord>().eq(RecycleRecord::getUserId, user.getId()));
        assertEquals(1, records.size());
        RecycleRecord record = records.get(0);
        assertEquals(file.getId(), record.getNodeId());
        assertEquals("hello.txt", record.getName());
        assertEquals("file", record.getType());
        assertEquals(5L, record.getTotalSize());

        // 物理文件已从 files 移到 trash
        assertFalse(Files.exists(originalPath));
        Path trashPath = userWithSpace.spacePath()
                .resolve(user.getId().toString())
                .resolve("trash")
                .resolve(record.getNodeId().toString().substring(0, 10))
                .resolve(record.getNodeId().toString())
                .resolve("hello.txt");
        assertTrue(Files.exists(trashPath));
    }

    @Test
    void shouldMoveFolderAndDescendantsToTrash() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo docs = createFolder(user.getId(), "docs", 0L);
        FileNodeVo report = fileService.upload(buildFile("report.txt", "Report"), user.getId(), 0L, null);
        moveFileToFolder(user.getId(), report, docs);

        Path originalFilePath = resolvePhysicalPath(userWithSpace, "docs/report.txt");
        assertTrue(Files.exists(originalFilePath));

        FileDeleteDto dto = new FileDeleteDto();
        dto.setIds(List.of(docs.getId()));

        List<OperationResultVo> results = fileRecycleService.deleteToTrash(dto, user.getId());

        assertEquals(1, results.size());
        assertEquals("success", results.get(0).getStatus());

        // 原 FileNode 被物理删除
        assertNull(fileMapper.selectById(docs.getId()));
        assertNull(fileMapper.selectById(report.getId()));

        // 回收站记录生成
        List<RecycleRecord> records = recycleRecordMapper.selectList(
                new LambdaQueryWrapper<RecycleRecord>().eq(RecycleRecord::getUserId, user.getId()));
        assertEquals(1, records.size());
        RecycleRecord record = records.get(0);
        assertEquals(docs.getId(), record.getNodeId());
        assertEquals("docs", record.getName());
        assertEquals("folder", record.getType());
        assertEquals(6L, record.getTotalSize());

        // 物理文件已按原结构移到 trash
        assertFalse(Files.exists(originalFilePath));
        Path trashPath = userWithSpace.spacePath()
                .resolve(user.getId().toString())
                .resolve("trash")
                .resolve(record.getNodeId().toString().substring(0, 10))
                .resolve(record.getNodeId().toString())
                .resolve("docs/report.txt");
        assertTrue(Files.exists(trashPath));
    }

    @Test
    void shouldMoveEmptyFolderToTrash() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo docs = createFolder(user.getId(), "docs", 0L);

        FileDeleteDto dto = new FileDeleteDto();
        dto.setIds(List.of(docs.getId()));

        List<OperationResultVo> results = fileRecycleService.deleteToTrash(dto, user.getId());

        assertEquals(1, results.size());
        assertEquals("success", results.get(0).getStatus());

        // 原 FileNode 被物理删除
        assertNull(fileMapper.selectById(docs.getId()));

        // 回收站记录生成
        List<RecycleRecord> records = recycleRecordMapper.selectList(
                new LambdaQueryWrapper<RecycleRecord>().eq(RecycleRecord::getUserId, user.getId()));
        assertEquals(1, records.size());
        RecycleRecord record = records.get(0);
        assertEquals(docs.getId(), record.getNodeId());
        assertEquals("docs", record.getName());
        assertEquals("folder", record.getType());
        assertEquals(0L, record.getTotalSize());

        // 回收站中存在对应的空目录占位
        Path trashFolderPath = userWithSpace.spacePath()
                .resolve(user.getId().toString())
                .resolve("trash")
                .resolve(record.getNodeId().toString().substring(0, 10))
                .resolve(record.getNodeId().toString())
                .resolve("docs");
        assertTrue(Files.exists(trashFolderPath));
        assertTrue(Files.isDirectory(trashFolderPath));
    }

    @Test
    void shouldRestoreSingleFileToOriginalLocation() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = fileService.upload(buildFile("hello.txt", "Hello"), user.getId(), 0L, null);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(file.getId()));
        List<OperationResultVo> deleteResults = fileRecycleService.deleteToTrash(deleteDto, user.getId());
        Long recordId = deleteResults.get(0).getNodeId();

        Path trashPath = resolveTrashPath(userWithSpace, file.getId(), "hello.txt");
        assertTrue(Files.exists(trashPath));

        FileExecuteRestoreDto restoreDto = new FileExecuteRestoreDto();
        RestoreItemDto item = new RestoreItemDto();
        item.setId(recordId);
        item.setStrategy(ConflictStrategy.OVERWRITE.getCode());
        restoreDto.setItems(List.of(item));

        List<OperationResultVo> results = fileRecycleService.restore(restoreDto, user.getId());

        assertEquals(1, results.size());
        assertEquals("success", results.get(0).getStatus());

        // 回收站记录被物理删除
        assertNull(recycleRecordMapper.selectById(recordId));

        // 新 FileNode 创建在根目录
        FilePageQueryDto query = new FilePageQueryDto();
        query.setParentId(0L);
        query.setPageNum(1L);
        query.setPageSize(10L);
        FileNodeVo restored = fileService.list(query, user.getId()).getRecords().get(0);
        assertEquals("hello.txt", restored.getName());
        assertEquals("file", restored.getType());
        assertEquals("/", restored.getPathName());

        // 物理文件移回 files
        assertFalse(Files.exists(trashPath));
        assertTrue(Files.exists(resolvePhysicalPath(userWithSpace, "hello.txt")));
    }

    @Test
    void shouldRestoreFolderAndDescendants() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo docs = createFolder(user.getId(), "docs", 0L);
        FileNodeVo report = fileService.upload(buildFile("report.txt", "Report"), user.getId(), 0L, null);
        moveFileToFolder(user.getId(), report, docs);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(docs.getId()));
        List<OperationResultVo> deleteResults = fileRecycleService.deleteToTrash(deleteDto, user.getId());
        Long recordId = deleteResults.get(0).getNodeId();

        Path trashPath = resolveTrashPath(userWithSpace, docs.getId(), "docs/report.txt");
        assertTrue(Files.exists(trashPath));

        FileExecuteRestoreDto restoreDto = new FileExecuteRestoreDto();
        RestoreItemDto item = new RestoreItemDto();
        item.setId(recordId);
        item.setStrategy(ConflictStrategy.OVERWRITE.getCode());
        restoreDto.setItems(List.of(item));

        List<OperationResultVo> results = fileRecycleService.restore(restoreDto, user.getId());

        assertEquals(1, results.size());
        assertEquals("success", results.get(0).getStatus());

        // 回收站记录被物理删除
        assertNull(recycleRecordMapper.selectById(recordId));

        // 文件夹与文件均恢复
        FilePageQueryDto query = new FilePageQueryDto();
        query.setParentId(0L);
        query.setPageNum(1L);
        query.setPageSize(10L);
        FileNodeVo restoredFolder = fileService.list(query, user.getId()).getRecords().get(0);
        assertEquals("docs", restoredFolder.getName());
        assertEquals("folder", restoredFolder.getType());

        FilePageQueryDto childQuery = new FilePageQueryDto();
        childQuery.setParentId(restoredFolder.getId());
        childQuery.setPageNum(1L);
        childQuery.setPageSize(10L);
        List<FileNodeVo> children = fileService.list(childQuery, user.getId()).getRecords();
        assertEquals(1, children.size());
        assertEquals("report.txt", children.get(0).getName());
        assertEquals("file", children.get(0).getType());

        // 物理文件移回 files
        assertFalse(Files.exists(trashPath));
        assertTrue(Files.exists(resolvePhysicalPath(userWithSpace, "docs/report.txt")));
    }

    @Test
    void shouldListTrashRecords() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo file = fileService.upload(buildFile("hello.txt", "Hello"), user.getId(), 0L, null);

        FileDeleteDto dto = new FileDeleteDto();
        dto.setIds(List.of(file.getId()));
        fileRecycleService.deleteToTrash(dto, user.getId());

        var page = fileRecycleService.listTrash(1L, 10L, user.getId());

        assertEquals(1L, page.getTotal());
        assertEquals(1, page.getRecords().size());
        assertEquals("hello.txt", page.getRecords().get(0).getName());
        assertEquals("file", page.getRecords().get(0).getType());
    }

    @Test
    void shouldPermanentDeleteFileAndFreeSpace() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = fileService.upload(buildFile("hello.txt", "Hello"), user.getId(), 0L, null);
        Path originalPath = resolvePhysicalPath(userWithSpace, "hello.txt");
        assertTrue(Files.exists(originalPath));

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(file.getId()));
        fileRecycleService.deleteToTrash(deleteDto, user.getId());

        userWithSpace = refreshUser(userWithSpace);
        assertEquals(5L, userWithSpace.user().getUsedSpace());
        Path trashPath = resolveTrashPath(userWithSpace, file.getId(), "hello.txt");
        assertTrue(Files.exists(trashPath));

        RecycleRecord record = getRecycleRecordByNodeId(file.getId(), user.getId());
        FilePermanentDeleteDto dto = new FilePermanentDeleteDto();
        dto.setIds(List.of(record.getId()));
        List<OperationResultVo> results = fileRecycleService.permanentDelete(dto, user.getId());

        assertEquals(1, results.size());
        assertEquals("success", results.get(0).getStatus());
        userWithSpace = refreshUser(userWithSpace);
        assertEquals(0L, userWithSpace.user().getUsedSpace());
        assertNull(recycleRecordMapper.selectById(record.getId()));
        assertFalse(Files.exists(trashPath));
    }

    @Test
    void shouldDetectRestoreConflictWhenTargetFileExists() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = fileService.upload(buildFile("hello.txt", "Hello"), user.getId(), 0L, null);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(file.getId()));
        fileRecycleService.deleteToTrash(deleteDto, user.getId());

        fileService.upload(buildFile("hello.txt", "World"), user.getId(), 0L, null);

        RecycleRecord record = getRecycleRecordByNodeId(file.getId(), user.getId());
        FilePreCheckRestoreDto dto = new FilePreCheckRestoreDto();
        dto.setIds(List.of(record.getId()));

        List<ConflictItemVo> conflicts = fileRecycleService.preCheckRestore(dto, user.getId());

        assertEquals(1, conflicts.size());
        assertEquals(record.getNodeId(), conflicts.get(0).getSourceId());
        assertEquals("hello.txt", conflicts.get(0).getSourceName());
        assertEquals("file", conflicts.get(0).getExistingType());
    }

    @Test
    void shouldRejectOverwriteWhenRestoreFolderConflicts() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo docs = createFolder(user.getId(), "docs", 0L);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(docs.getId()));
        fileRecycleService.deleteToTrash(deleteDto, user.getId());

        createFolder(user.getId(), "docs", 0L);

        RecycleRecord record = getRecycleRecordByNodeId(docs.getId(), user.getId());
        FileExecuteRestoreDto dto = new FileExecuteRestoreDto();
        RestoreItemDto item = new RestoreItemDto();
        item.setId(record.getId());
        item.setStrategy(ConflictStrategy.OVERWRITE.getCode());
        dto.setItems(List.of(item));

        assertThrows(BusinessException.class, () -> fileRecycleService.restore(dto, user.getId()));
    }

    @Test
    void shouldRejectAutoRenameWhenRestoreFolderConflicts() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo docs = createFolder(user.getId(), "docs", 0L);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(docs.getId()));
        fileRecycleService.deleteToTrash(deleteDto, user.getId());

        createFolder(user.getId(), "docs", 0L);

        RecycleRecord record = getRecycleRecordByNodeId(docs.getId(), user.getId());
        FileExecuteRestoreDto dto = new FileExecuteRestoreDto();
        RestoreItemDto item = new RestoreItemDto();
        item.setId(record.getId());
        item.setStrategy(ConflictStrategy.AUTO_RENAME.getCode());
        dto.setItems(List.of(item));

        assertThrows(BusinessException.class, () -> fileRecycleService.restore(dto, user.getId()));
    }

    @Test
    void shouldAutoRenameWhenRestoreConflicts() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = fileService.upload(buildFile("hello.txt", "Hello"), user.getId(), 0L, null);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(file.getId()));
        fileRecycleService.deleteToTrash(deleteDto, user.getId());

        fileService.upload(buildFile("hello.txt", "World"), user.getId(), 0L, null);

        RecycleRecord record = getRecycleRecordByNodeId(file.getId(), user.getId());
        FileExecuteRestoreDto dto = new FileExecuteRestoreDto();
        RestoreItemDto item = new RestoreItemDto();
        item.setId(record.getId());
        item.setStrategy(ConflictStrategy.AUTO_RENAME.getCode());
        dto.setItems(List.of(item));
        List<OperationResultVo> results = fileRecycleService.restore(dto, user.getId());

        assertEquals(1, results.size());
        assertEquals("success", results.get(0).getStatus());
        assertNotNull(results.get(0).getNewName());
        assertTrue(results.get(0).getNewName().startsWith("hello"));

        FilePageQueryDto query = new FilePageQueryDto();
        query.setParentId(0L);
        query.setPageNum(1L);
        query.setPageSize(10L);
        List<FileNodeVo> files = fileService.list(query, user.getId()).getRecords();
        assertEquals(2, files.size());
    }

    @Test
    void shouldAutoRenameUsingMaxSuffixPlusOne() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = fileService.upload(buildFile("a.txt", "first"), user.getId(), 0L, null);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(file.getId()));
        fileRecycleService.deleteToTrash(deleteDto, user.getId());

        fileService.upload(buildFile("a.txt", "second"), user.getId(), 0L, null);
        fileService.upload(buildFile("a.1.txt", "third"), user.getId(), 0L, null);
        fileService.upload(buildFile("a.2.txt", "fourth"), user.getId(), 0L, null);

        RecycleRecord record = getRecycleRecordByNodeId(file.getId(), user.getId());
        FileExecuteRestoreDto dto = new FileExecuteRestoreDto();
        RestoreItemDto item = new RestoreItemDto();
        item.setId(record.getId());
        item.setStrategy(ConflictStrategy.AUTO_RENAME.getCode());
        dto.setItems(List.of(item));
        List<OperationResultVo> results = fileRecycleService.restore(dto, user.getId());

        assertEquals(1, results.size());
        assertEquals("success", results.get(0).getStatus());
        assertEquals("a.3.txt", results.get(0).getNewName());
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
        item.setStrategy(ConflictStrategy.OVERWRITE.getCode());
        dto.setItems(List.of(item));
        fileOperationService.move(dto, userId);
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

    private Path resolveTrashPath(UserWithSpace userWithSpace, Long nodeId, String relativePath) {
        String idStr = nodeId.toString();
        String prefix = idStr.length() >= 10 ? idStr.substring(0, 10) : idStr;
        return userWithSpace.spacePath()
                .resolve(userWithSpace.user().getId().toString())
                .resolve("trash")
                .resolve(prefix)
                .resolve(idStr)
                .resolve(relativePath);
    }

    private UserWithSpace refreshUser(UserWithSpace original) {
        UserVo user = userService.getById(original.user().getId());
        return new UserWithSpace(user, original.spacePath());
    }

    private RecycleRecord getRecycleRecordByNodeId(Long nodeId, Long userId) {
        LambdaQueryWrapper<RecycleRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RecycleRecord::getNodeId, nodeId).eq(RecycleRecord::getUserId, userId);
        return recycleRecordMapper.selectOne(wrapper);
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
        userDto.setUsername("recycleUser" + quota + "-" + System.nanoTime());
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
}
