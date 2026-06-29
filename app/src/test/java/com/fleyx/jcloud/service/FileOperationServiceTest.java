package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.dto.FilePreCheckOperationDto;
import com.fleyx.jcloud.model.dto.FileRenameDto;
import com.fleyx.jcloud.model.dto.OperationItemDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件组织操作服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FileOperationServiceTest {

    @Autowired
    private FileOperationService fileOperationService;

    @Autowired
    private FileService fileService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private FileMapper fileMapper;

    @TempDir
    Path tempDir;

    @Test
    void shouldRenameFile() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = fileService.upload(buildFile("old.txt", "content"), user.getId(), FileNodeConstants.ROOT_ID, null);

        FileRenameDto dto = new FileRenameDto();
        dto.setId(file.getId());
        dto.setNewName("new.txt");

        FileNodeVo renamed = fileOperationService.rename(dto, user.getId());

        assertEquals("new.txt", renamed.getName());
        Path oldPath = resolvePhysicalPath(userWithSpace, "old.txt");
        Path newPath = resolvePhysicalPath(userWithSpace, "new.txt");
        assertTrue(Files.notExists(oldPath));
        assertTrue(Files.exists(newPath));
    }

    @Test
    void shouldRejectRenameWhenNameConflict() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        fileService.upload(buildFile("a.txt", "A"), user.getId(), FileNodeConstants.ROOT_ID, null);
        FileNodeVo b = fileService.upload(buildFile("b.txt", "B"), user.getId(), FileNodeConstants.ROOT_ID, null);

        FileRenameDto dto = new FileRenameDto();
        dto.setId(b.getId());
        dto.setNewName("a.txt");

        assertThrows(BusinessException.class, () -> fileOperationService.rename(dto, user.getId()));
    }

    @Test
    void shouldCreateFolder() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(FileNodeConstants.ROOT_ID);
        dto.setName("docs");

        FileNodeVo folder = fileOperationService.createFolder(dto, user.getId());

        assertNotNull(folder.getId());
        assertEquals("docs", folder.getName());
        assertEquals("folder", folder.getType());
    }

    @Test
    void shouldCreateFolderWhenParentIdIsZero() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId("0");
        dto.setName("docs");

        FileNodeVo folder = fileOperationService.createFolder(dto, user.getId());

        assertNotNull(folder.getId());
        assertEquals("docs", folder.getName());
        assertEquals("folder", folder.getType());
        assertEquals(FileNodeConstants.ROOT_ID, folder.getParentId());
    }

    @Test
    void shouldRenameFolderAndUpdateDescendantPathNames() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo docs = fileOperationService.createFolder(buildCreateFolderDto(FileNodeConstants.ROOT_ID, "docs"), user.getId());
        FileNodeVo report = fileService.upload(buildFile("report.txt", "R"), user.getId(), docs.getId(), null);

        FileRenameDto dto = new FileRenameDto();
        dto.setId(docs.getId());
        dto.setNewName("documents");

        FileNodeVo renamed = fileOperationService.rename(dto, user.getId());

        assertEquals("documents", renamed.getName());
        FileNode updatedReport = fileMapper.selectById(report.getId());
        assertEquals("report.txt", updatedReport.getName());
        assertTrue(Files.exists(resolvePhysicalPath(userWithSpace, "documents/report.txt")));
    }

    @Test
    void shouldNotAffectSiblingFolderWhenRenamingFolder() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo docs = fileOperationService.createFolder(buildCreateFolderDto(FileNodeConstants.ROOT_ID, "docs"), user.getId());
        FileNodeVo docs2 = fileOperationService.createFolder(buildCreateFolderDto(FileNodeConstants.ROOT_ID, "docs2"), user.getId());
        FileNodeVo report = fileService.upload(buildFile("report.txt", "R"), user.getId(), docs2.getId(), null);

        FileRenameDto dto = new FileRenameDto();
        dto.setId(docs.getId());
        dto.setNewName("documents");
        fileOperationService.rename(dto, user.getId());

        FileNode unaffected = fileMapper.selectById(report.getId());
        assertEquals("report.txt", unaffected.getName());
    }

    @Test
    void shouldRejectCreateFolderWhenNameConflict() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(FileNodeConstants.ROOT_ID);
        dto.setName("docs");
        fileOperationService.createFolder(dto, user.getId());

        assertThrows(BusinessException.class, () -> fileOperationService.createFolder(dto, user.getId()));
    }

    @Test
    void shouldMoveFileToFolder() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo folder = createFolder(user.getId(), "docs", FileNodeConstants.ROOT_ID);
        FileNodeVo file = fileService.upload(buildFile("report.txt", "report"), user.getId(), FileNodeConstants.ROOT_ID, null);

        FileExecuteOperationDto dto = buildOperationDto("move", folder.getId(), file);
        List<OperationResultVo> results = fileOperationService.move(dto, user.getId());

        assertEquals(1, results.size());
        assertEquals("success", results.get(0).getStatus());
        Path sourcePath = resolvePhysicalPath(userWithSpace, "report.txt");
        Path targetPath = resolvePhysicalPath(userWithSpace, "docs/report.txt");
        assertTrue(Files.notExists(sourcePath));
        assertTrue(Files.exists(targetPath));
    }

    @Test
    void shouldSkipOnMoveConflict() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), "docs", FileNodeConstants.ROOT_ID);
        FileNodeVo first = fileService.upload(buildFile("same.txt", "first"), user.getId(), FileNodeConstants.ROOT_ID, null);
        fileOperationService.move(buildOperationDto("move", folder.getId(), first), user.getId());
        FileNodeVo fileToMove = fileService.upload(buildFile("same.txt", "second"), user.getId(), FileNodeConstants.ROOT_ID, null);

        FileExecuteOperationDto dto = buildOperationDto("move", folder.getId(), fileToMove);
        dto.getItems().get(0).setStrategy(ConflictStrategy.SKIP.getCode());
        List<OperationResultVo> results = fileOperationService.move(dto, user.getId());

        assertEquals("skipped", results.get(0).getStatus());
    }

    @Test
    void shouldAutoRenameOnMoveConflict() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), "docs", FileNodeConstants.ROOT_ID);
        FileNodeVo first = fileService.upload(buildFile("same.txt", "first"), user.getId(), FileNodeConstants.ROOT_ID, null);
        fileOperationService.move(buildOperationDto("move", folder.getId(), first), user.getId());
        FileNodeVo fileToMove = fileService.upload(buildFile("same.txt", "second"), user.getId(), FileNodeConstants.ROOT_ID, null);

        FileExecuteOperationDto dto = buildOperationDto("move", folder.getId(), fileToMove);
        dto.getItems().get(0).setStrategy(ConflictStrategy.KEEP.getCode());
        List<OperationResultVo> results = fileOperationService.move(dto, user.getId());

        assertEquals("success", results.get(0).getStatus());
        assertEquals("same(1).txt", results.get(0).getNewName());
    }

    @Test
    void shouldPreCheckMoveConflict() throws Exception {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), "docs", FileNodeConstants.ROOT_ID);
        FileNodeVo first = fileService.upload(buildFile("same.txt", "first"), user.getId(), FileNodeConstants.ROOT_ID, null);
        fileOperationService.move(buildOperationDto("move", folder.getId(), first), user.getId());
        FileNodeVo fileToMove = fileService.upload(buildFile("same.txt", "second"), user.getId(), FileNodeConstants.ROOT_ID, null);

        FilePreCheckOperationDto dto = buildPreCheckDto("move", folder.getId(), fileToMove);
        List<ConflictItemVo> conflicts = fileOperationService.preCheckOperation(dto, user.getId());

        assertEquals(1, conflicts.size());
        assertEquals("same.txt", conflicts.get(0).getSourceName());
        assertEquals("same.txt", conflicts.get(0).getExistingName());
    }

    @Test
    void shouldCopyFile() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo folder = createFolder(user.getId(), "backup", FileNodeConstants.ROOT_ID);
        FileNodeVo file = fileService.upload(buildFile("note.txt", "note"), user.getId(), FileNodeConstants.ROOT_ID, null);

        FileExecuteOperationDto dto = buildOperationDto("copy", folder.getId(), file);
        List<OperationResultVo> results = fileOperationService.copy(dto, user.getId());

        assertEquals("success", results.get(0).getStatus());
        Path original = resolvePhysicalPath(userWithSpace, "note.txt");
        Path copied = resolvePhysicalPath(userWithSpace, "backup/note.txt");
        assertTrue(Files.exists(original));
        assertTrue(Files.exists(copied));
        assertEquals(Files.size(original), Files.size(copied));
    }

    @Test
    void shouldCopyFileWhenQuotaIsZero() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace(0L);
        UserVo user = userWithSpace.user();
        FileNodeVo folder = createFolder(user.getId(), "backup", FileNodeConstants.ROOT_ID);
        FileNodeVo file = fileService.upload(buildFile("note.txt", "note"), user.getId(), FileNodeConstants.ROOT_ID, null);

        FileExecuteOperationDto dto = buildOperationDto("copy", folder.getId(), file);
        List<OperationResultVo> results = fileOperationService.copy(dto, user.getId());

        assertEquals("success", results.get(0).getStatus());
        Path original = resolvePhysicalPath(userWithSpace, "note.txt");
        Path copied = resolvePhysicalPath(userWithSpace, "backup/note.txt");
        assertTrue(Files.exists(original));
        assertTrue(Files.exists(copied));
    }

    @Test
    void shouldCopyFolderRecursively() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo docs = createFolder(user.getId(), "docs", FileNodeConstants.ROOT_ID);
        FileNodeVo report = fileService.upload(buildFile("report.txt", "report"), user.getId(), FileNodeConstants.ROOT_ID, null);
        FileExecuteOperationDto moveDto = buildOperationDto("move", docs.getId(), report);
        fileOperationService.move(moveDto, user.getId());

        FileNodeVo backup = createFolder(user.getId(), "backup", FileNodeConstants.ROOT_ID);
        FileExecuteOperationDto copyDto = buildOperationDto("copy", backup.getId(), docs);
        List<OperationResultVo> results = fileOperationService.copy(copyDto, user.getId());

        assertEquals("success", results.get(0).getStatus());
        Path copiedFile = resolvePhysicalPath(userWithSpace, "backup/docs/report.txt");
        assertTrue(Files.exists(copiedFile));
    }

    @Test
    void shouldRejectOperationOnOtherUsersFile() {
        UserVo userA = prepareUserWithStorageSpace().user();
        UserVo userB = prepareUserWithStorageSpace().user();
        FileNodeVo folderA = createFolder(userA.getId(), "docs", FileNodeConstants.ROOT_ID);

        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(folderA.getId());
        dto.setName("nested");

        assertThrows(BusinessException.class, () -> fileOperationService.createFolder(dto, userB.getId()));
    }

    private FileNodeVo createFolder(String userId, String name, String parentId) {
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(parentId);
        dto.setName(name);
        return fileOperationService.createFolder(dto, userId);
    }

    private FilePreCheckOperationDto buildPreCheckDto(String type, String targetParentId, FileNodeVo... files) {
        FilePreCheckOperationDto dto = new FilePreCheckOperationDto();
        dto.setType(type);
        dto.setTargetParentId(targetParentId);
        dto.setItems(buildItems(files));
        return dto;
    }

    private FileExecuteOperationDto buildOperationDto(String type, String targetParentId, FileNodeVo... files) {
        FileExecuteOperationDto dto = new FileExecuteOperationDto();
        dto.setType(type);
        dto.setTargetParentId(targetParentId);
        dto.setItems(buildItems(files));
        return dto;
    }

    private List<OperationItemDto> buildItems(FileNodeVo... files) {
        return java.util.Arrays.stream(files)
                .map(file -> {
                    OperationItemDto item = new OperationItemDto();
                    item.setId(file.getId());
                    item.setName(file.getName());
                    item.setStrategy(ConflictStrategy.OVERWRITE.getCode());
                    return item;
                })
                .toList();
    }

    private FileCreateFolderDto buildCreateFolderDto(String parentId, String name) {
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(parentId);
        dto.setName(name);
        return dto;
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
