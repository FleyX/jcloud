package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FilePageQueryDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 文件夹路径解析服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FolderPathServiceTest {

    @Autowired
    private FolderPathService folderPathService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private FileOperationService fileOperationService;

    @Autowired
    private FileService fileService;

    @TempDir
    Path tempDir;

    @Test
    void shouldReturnParentIdWhenRelativePathIsEmpty() {
        UserVo user = prepareUser();

        String result = folderPathService.resolveOrCreateFolderPath(user.getId(), FileNodeConstants.ROOT_ID, "  ");

        assertEquals(FileNodeConstants.ROOT_ID, result);
    }

    @Test
    void shouldReturnParentIdWhenRelativePathHasNoDirectory() {
        UserVo user = prepareUser();

        String result = folderPathService.resolveOrCreateFolderPath(user.getId(), FileNodeConstants.ROOT_ID, "main.java");

        assertEquals(FileNodeConstants.ROOT_ID, result);
    }

    @Test
    void shouldCreateNestedFolders() {
        UserVo user = prepareUser();

        String result = folderPathService.resolveOrCreateFolderPath(user.getId(), FileNodeConstants.ROOT_ID,
                "project/src/main.java");

        assertNotNull(result);

        FileNodeVo projectFolder = findFolder(user.getId(), FileNodeConstants.ROOT_ID, "project");
        assertNotNull(projectFolder);
        FileNodeVo srcFolder = findFolder(user.getId(), projectFolder.getId(), "src");
        assertNotNull(srcFolder);
        assertEquals(srcFolder.getId(), result);
    }

    @Test
    void shouldReuseExistingFolders() {
        UserVo user = prepareUser();
        FileNodeVo existingProject = fileOperationService.createFolder(buildCreateFolderDto(FileNodeConstants.ROOT_ID, "project"), user.getId());

        String result = folderPathService.resolveOrCreateFolderPath(user.getId(), FileNodeConstants.ROOT_ID,
                "project/src/main.java");

        FileNodeVo projectFolder = findFolder(user.getId(), FileNodeConstants.ROOT_ID, "project");
        assertEquals(existingProject.getId(), projectFolder.getId());

        FileNodeVo srcFolder = findFolder(user.getId(), projectFolder.getId(), "src");
        assertNotNull(srcFolder);
        assertEquals(srcFolder.getId(), result);
    }

    @Test
    void shouldRejectPathTraversal() {
        UserVo user = prepareUser();

        assertThrows(BusinessException.class,
                () -> folderPathService.resolveOrCreateFolderPath(user.getId(), FileNodeConstants.ROOT_ID,
                        "../project/main.java"));
    }

    @Test
    void shouldNormalizeRedundantSegments() {
        UserVo user = prepareUser();

        String result = folderPathService.resolveOrCreateFolderPath(user.getId(), FileNodeConstants.ROOT_ID,
                "project/./src/main.java");

        FileNodeVo projectFolder = findFolder(user.getId(), FileNodeConstants.ROOT_ID, "project");
        assertNotNull(projectFolder);
        FileNodeVo srcFolder = findFolder(user.getId(), projectFolder.getId(), "src");
        assertNotNull(srcFolder);
        assertEquals(srcFolder.getId(), result);
    }

    private FileNodeVo findFolder(String userId, String parentId, String name) {
        FilePageQueryDto query = new FilePageQueryDto();
        query.setParentId(parentId);
        query.setName(name);
        return fileService.list(query, userId).getRecords().stream()
                .filter(node -> "folder".equals(node.getType()) && name.equals(node.getName()))
                .findFirst()
                .orElse(null);
    }

    private FileCreateFolderDto buildCreateFolderDto(String parentId, String name) {
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(parentId);
        dto.setName(name);
        return dto;
    }

    private UserVo prepareUser() {
        Path spacePath = tempDir.resolve("space-" + System.nanoTime());
        StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
        spaceDto.setName("用户空间");
        spaceDto.setPath(spacePath.toString());
        StorageSpaceVo space = storageSpaceService.save(spaceDto);

        UserSaveDto userDto = new UserSaveDto();
        userDto.setUsername("user_" + Long.toUnsignedString(System.nanoTime(), 36));
        userDto.setPassword("123456");
        userDto.setStorageSpaceId(space.getId());
        userDto.setQuota(10L);
        userDto.setQuotaUnit("GB");
        return userService.saveUser(userDto);
    }
}
