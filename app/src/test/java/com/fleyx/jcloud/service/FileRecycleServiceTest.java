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
        FileNodeVo file = fileService.upload(buildFile("hello.txt", "Hello"), user.getId(), FileNodeConstants.ROOT_ID, null);
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
        assertNotNull(record.getId());
        assertEquals("hello.txt", record.getName());
        assertEquals("file", record.getType());
        assertEquals(5L, record.getTotalSize());

        // 物理文件已从 files 移到 trash
        assertFalse(Files.exists(originalPath));
        Path trashPath = userWithSpace.spacePath()
                .resolve("trash")
                .resolve(user.getUsername())
                .resolve(record.getId().toString())
                .resolve("hello.txt");
        assertTrue(Files.exists(trashPath));
    }

    @Test
    void shouldMoveFolderAndDescendantsToTrash() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo docs = createFolder(user.getId(), "docs", FileNodeConstants.ROOT_ID);
        FileNodeVo report = fileService.upload(buildFile("report.txt", "Report"), user.getId(), FileNodeConstants.ROOT_ID, null);
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
        assertNotNull(record.getId());
        assertEquals("docs", record.getName());
        assertEquals("folder", record.getType());
        assertEquals(6L, record.getTotalSize());

        // 物理文件已按原结构移到 trash
        assertFalse(Files.exists(originalFilePath));
        Path trashPath = userWithSpace.spacePath()
                .resolve("trash")
                .resolve(user.getUsername())
                .resolve(record.getId().toString())
                .resolve("docs/report.txt");
        assertTrue(Files.exists(trashPath));
    }

    @Test
    void shouldMoveEmptyFolderToTrash() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo docs = createFolder(user.getId(), "docs", FileNodeConstants.ROOT_ID);

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
        assertNotNull(record.getId());
        assertEquals("docs", record.getName());
        assertEquals("folder", record.getType());
        assertEquals(0L, record.getTotalSize());

        // 回收站中存在对应的空目录占位
        Path trashFolderPath = userWithSpace.spacePath()
                .resolve("trash")
                .resolve(user.getUsername())
                .resolve(record.getId().toString())
                .resolve("docs");
        assertTrue(Files.exists(trashFolderPath));
        assertTrue(Files.isDirectory(trashFolderPath));
    }

    @Test
    void shouldRestoreSingleFileToOriginalLocation() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = fileService.upload(buildFile("hello.txt", "Hello"), user.getId(), FileNodeConstants.ROOT_ID, null);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(file.getId()));
        List<OperationResultVo> deleteResults = fileRecycleService.deleteToTrash(deleteDto, user.getId());
        String recordId = deleteResults.get(0).getNodeId();

        Path trashPath = resolveTrashPath(userWithSpace, recordId, "hello.txt");
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
        query.setParentId(FileNodeConstants.ROOT_ID);
        query.setPageNum(1L);
        query.setPageSize(10L);
        FileNodeVo restored = fileService.list(query, user.getId()).getRecords().get(0);
        assertEquals("hello.txt", restored.getName());
        assertEquals("file", restored.getType());

        // 物理文件移回 files
        assertFalse(Files.exists(trashPath));
        assertTrue(Files.exists(resolvePhysicalPath(userWithSpace, "hello.txt")));
    }

    @Test
    void shouldRestoreFolderAndDescendants() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo docs = createFolder(user.getId(), "docs", FileNodeConstants.ROOT_ID);
        FileNodeVo report = fileService.upload(buildFile("report.txt", "Report"), user.getId(), FileNodeConstants.ROOT_ID, null);
        moveFileToFolder(user.getId(), report, docs);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(docs.getId()));
        List<OperationResultVo> deleteResults = fileRecycleService.deleteToTrash(deleteDto, user.getId());
        String recordId = deleteResults.get(0).getNodeId();

        Path trashPath = resolveTrashPath(userWithSpace, recordId, "docs/report.txt");
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
        query.setParentId(FileNodeConstants.ROOT_ID);
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
        FileNodeVo file = fileService.upload(buildFile("hello.txt", "Hello"), user.getId(), FileNodeConstants.ROOT_ID, null);

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
        FileNodeVo file = fileService.upload(buildFile("hello.txt", "Hello"), user.getId(), FileNodeConstants.ROOT_ID, null);
        Path originalPath = resolvePhysicalPath(userWithSpace, "hello.txt");
        assertTrue(Files.exists(originalPath));

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(file.getId()));
        List<OperationResultVo> deleteResults = fileRecycleService.deleteToTrash(deleteDto, user.getId());
        String recordId = deleteResults.get(0).getNodeId();

        userWithSpace = refreshUser(userWithSpace);
        assertEquals(5L, userWithSpace.user().getUsedSpace());
        Path trashPath = resolveTrashPath(userWithSpace, recordId, "hello.txt");
        assertTrue(Files.exists(trashPath));

        RecycleRecord record = recycleRecordMapper.selectById(recordId);
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
        FileNodeVo file = fileService.upload(buildFile("hello.txt", "Hello"), user.getId(), FileNodeConstants.ROOT_ID, null);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(file.getId()));
        fileRecycleService.deleteToTrash(deleteDto, user.getId());

        fileService.upload(buildFile("hello.txt", "World"), user.getId(), FileNodeConstants.ROOT_ID, null);

        RecycleRecord record = recycleRecordMapper.selectList(
                new LambdaQueryWrapper<RecycleRecord>().eq(RecycleRecord::getUserId, user.getId())).get(0);
        FilePreCheckRestoreDto dto = new FilePreCheckRestoreDto();
        dto.setIds(List.of(record.getId()));

        List<ConflictItemVo> conflicts = fileRecycleService.preCheckRestore(dto, user.getId());

        assertEquals(1, conflicts.size());
        assertEquals(record.getId(), conflicts.get(0).getSourceId());
        assertEquals("hello.txt", conflicts.get(0).getSourceName());
        assertEquals("file", conflicts.get(0).getExistingType());
    }

    @Test
    void shouldMergeRestoreFolderEvenWithOverwriteStrategy() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo docs = createFolder(user.getId(), "docs", FileNodeConstants.ROOT_ID);
        FileNodeVo report = fileService.upload(buildFile("report.txt", "Report"), user.getId(), FileNodeConstants.ROOT_ID, null);
        moveFileToFolder(user.getId(), report, docs);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(docs.getId()));
        fileRecycleService.deleteToTrash(deleteDto, user.getId());

        createFolder(user.getId(), "docs", FileNodeConstants.ROOT_ID);

        RecycleRecord record = recycleRecordMapper.selectList(
                new LambdaQueryWrapper<RecycleRecord>().eq(RecycleRecord::getUserId, user.getId())).get(0);
        FileExecuteRestoreDto dto = new FileExecuteRestoreDto();
        RestoreItemDto item = new RestoreItemDto();
        item.setId(record.getId());
        item.setStrategy(ConflictStrategy.OVERWRITE.getCode());
        dto.setItems(List.of(item));

        List<OperationResultVo> results = fileRecycleService.restore(dto, user.getId());

        assertEquals(1, results.size());
        assertEquals("success", results.get(0).getStatus());
        Path restoredFile = resolvePhysicalPath(userWithSpace, "docs/report.txt");
        assertTrue(Files.exists(restoredFile));
    }

    @Test
    void shouldMergeRestoreFolderWhenTargetFolderExists() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo docs = createFolder(user.getId(), "docs", FileNodeConstants.ROOT_ID);
        FileNodeVo report = fileService.upload(buildFile("report.txt", "Report"), user.getId(), FileNodeConstants.ROOT_ID, null);
        moveFileToFolder(user.getId(), report, docs);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(docs.getId()));
        fileRecycleService.deleteToTrash(deleteDto, user.getId());

        FileNodeVo existingDocs = createFolder(user.getId(), "docs", FileNodeConstants.ROOT_ID);

        RecycleRecord record = recycleRecordMapper.selectList(
                new LambdaQueryWrapper<RecycleRecord>().eq(RecycleRecord::getUserId, user.getId())).get(0);
        FileExecuteRestoreDto dto = new FileExecuteRestoreDto();
        RestoreItemDto item = new RestoreItemDto();
        item.setId(record.getId());
        item.setStrategy(ConflictStrategy.KEEP.getCode());
        dto.setItems(List.of(item));

        List<OperationResultVo> results = fileRecycleService.restore(dto, user.getId());

        assertEquals(1, results.size());
        assertEquals("success", results.get(0).getStatus());
        Path restoredFile = resolvePhysicalPath(userWithSpace, "docs/report.txt");
        assertTrue(Files.exists(restoredFile));
    }

    @Test
    void shouldDetectFileConflictsInsideRestoredFolder() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo folder = createFolder(user.getId(), "1", FileNodeConstants.ROOT_ID);
        FileNodeVo file1 = fileService.upload(buildFile("archive.zip", "A"), user.getId(), FileNodeConstants.ROOT_ID, null);
        FileNodeVo file2 = fileService.upload(buildFile("archive (3).zip", "B"), user.getId(), FileNodeConstants.ROOT_ID, null);
        moveFileToFolder(user.getId(), file1, folder);
        moveFileToFolder(user.getId(), file2, folder);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(folder.getId()));
        fileRecycleService.deleteToTrash(deleteDto, user.getId());

        FileNodeVo existingFolder = createFolder(user.getId(), "1", FileNodeConstants.ROOT_ID);
        fileService.upload(buildFile("archive.zip", "ExistingA"), user.getId(), existingFolder.getId(), null);
        fileService.upload(buildFile("archive (3).zip", "ExistingB"), user.getId(), existingFolder.getId(), null);

        RecycleRecord record = recycleRecordMapper.selectList(
                new LambdaQueryWrapper<RecycleRecord>().eq(RecycleRecord::getUserId, user.getId())).get(0);
        FilePreCheckRestoreDto preCheckDto = new FilePreCheckRestoreDto();
        preCheckDto.setIds(List.of(record.getId()));

        List<ConflictItemVo> conflicts = fileRecycleService.preCheckRestore(preCheckDto, user.getId());

        assertEquals(3, conflicts.size());
        long fileConflicts = conflicts.stream().filter(c -> "file".equals(c.getType())).count();
        assertEquals(2, fileConflicts);
    }

    @Test
    void shouldAutoRenameWhenRestoreConflicts() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = fileService.upload(buildFile("hello.txt", "Hello"), user.getId(), FileNodeConstants.ROOT_ID, null);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(file.getId()));
        fileRecycleService.deleteToTrash(deleteDto, user.getId());

        fileService.upload(buildFile("hello.txt", "World"), user.getId(), FileNodeConstants.ROOT_ID, null);

        RecycleRecord record = recycleRecordMapper.selectList(
                new LambdaQueryWrapper<RecycleRecord>().eq(RecycleRecord::getUserId, user.getId())).get(0);
        FileExecuteRestoreDto dto = new FileExecuteRestoreDto();
        RestoreItemDto item = new RestoreItemDto();
        item.setId(record.getId());
        item.setStrategy(ConflictStrategy.KEEP.getCode());
        dto.setItems(List.of(item));
        List<OperationResultVo> results = fileRecycleService.restore(dto, user.getId());

        assertEquals(1, results.size());
        assertEquals("success", results.get(0).getStatus());
        assertNotNull(results.get(0).getNewName());
        assertEquals("hello(1).txt", results.get(0).getNewName());

        FilePageQueryDto query = new FilePageQueryDto();
        query.setParentId(FileNodeConstants.ROOT_ID);
        query.setPageNum(1L);
        query.setPageSize(10L);
        List<FileNodeVo> files = fileService.list(query, user.getId()).getRecords();
        assertEquals(2, files.size());
    }

    @Test
    void shouldAutoRenameUsingMaxSuffixPlusOne() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = fileService.upload(buildFile("a.txt", "first"), user.getId(), FileNodeConstants.ROOT_ID, null);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(file.getId()));
        fileRecycleService.deleteToTrash(deleteDto, user.getId());

        fileService.upload(buildFile("a.txt", "second"), user.getId(), FileNodeConstants.ROOT_ID, null);
        fileService.upload(buildFile("a(1).txt", "third"), user.getId(), FileNodeConstants.ROOT_ID, null);
        fileService.upload(buildFile("a(2).txt", "fourth"), user.getId(), FileNodeConstants.ROOT_ID, null);

        RecycleRecord record = recycleRecordMapper.selectList(
                new LambdaQueryWrapper<RecycleRecord>().eq(RecycleRecord::getUserId, user.getId())).get(0);
        FileExecuteRestoreDto dto = new FileExecuteRestoreDto();
        RestoreItemDto item = new RestoreItemDto();
        item.setId(record.getId());
        item.setStrategy(ConflictStrategy.KEEP.getCode());
        dto.setItems(List.of(item));
        List<OperationResultVo> results = fileRecycleService.restore(dto, user.getId());

        assertEquals(1, results.size());
        assertEquals("success", results.get(0).getStatus());
        assertEquals("a(3).txt", results.get(0).getNewName());
    }

    private FileNodeVo createFolder(String userId, String name, String parentId) {
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(parentId);
        dto.setName(name);
        return fileOperationService.createFolder(dto, userId);
    }

    private void moveFileToFolder(String userId, FileNodeVo file, FileNodeVo folder) {
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
                .resolve("files")
                .resolve(userWithSpace.user().getUsername())
                .resolve(relativePath);
    }

    private Path resolveTrashPath(UserWithSpace userWithSpace, String recordId, String relativePath) {
        String idStr = recordId;
        return userWithSpace.spacePath()
                .resolve("trash")
                .resolve(userWithSpace.user().getUsername())
                .resolve(idStr)
                .resolve(relativePath);
    }

    private UserWithSpace refreshUser(UserWithSpace original) {
        UserVo user = userService.getById(original.user().getId());
        return new UserWithSpace(user, original.spacePath());
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
