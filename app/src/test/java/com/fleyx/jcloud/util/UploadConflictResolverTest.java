package com.fleyx.jcloud.util;

import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.service.FileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 上传冲突解决器 UploadConflictResolver 集成测试。
 */
@Transactional
class UploadConflictResolverTest extends IntegrationTestBase {

    @Autowired
    private UploadConflictResolver uploadConflictResolver;

    @Autowired
    private FileService fileService;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private UserMapper userMapper;

    private FileNodeVo upload(UserWithSpace userWithSpace, String name) {
        MultipartFile file = new MockMultipartFile(
                "file", name, "text/plain", "jcloud-content".getBytes());
        return fileService.upload(file, userWithSpace.user().getId(), FileNodeConstants.ROOT_ID, null);
    }

    @Test
    void shouldReturnOriginalNameWhenNoSameNameAndNoStrategy() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();

        FileConflictResolver.ConflictResolution resolution = uploadConflictResolver.resolve(
                userWithSpace.user().getId(), FileNodeConstants.ROOT_ID, "report.pdf", null);

        assertEquals("report.pdf", resolution.finalName());
        assertNull(resolution.existingToReplace());
        assertFalse(resolution.skipped());
    }

    @Test
    void shouldThrowBusinessExceptionWhenSameNameAndNoStrategy() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        upload(userWithSpace, "report.pdf");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> uploadConflictResolver.resolve(
                        userWithSpace.user().getId(), FileNodeConstants.ROOT_ID, "report.pdf", null));

        assertEquals(ResultCode.BUSINESS_ERROR, ex.getResultCode());
        assertEquals("目标文件已存在", ex.getMessage());
    }

    @Test
    void shouldAutoRenameWhenSameNameAndKeepStrategy() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        upload(userWithSpace, "report.pdf");

        FileConflictResolver.ConflictResolution resolution = uploadConflictResolver.resolve(
                userWithSpace.user().getId(), FileNodeConstants.ROOT_ID, "report.pdf",
                ConflictStrategy.KEEP.getCode());

        assertEquals("report(1).pdf", resolution.finalName());
        assertNull(resolution.existingToReplace());
        assertFalse(resolution.skipped());
    }

    @Test
    void shouldPickNextFreeSuffixWhenKeepStrategyAndNumberedNamesExist() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        upload(userWithSpace, "report.pdf");
        upload(userWithSpace, "report(1).pdf");

        FileConflictResolver.ConflictResolution resolution = uploadConflictResolver.resolve(
                userWithSpace.user().getId(), FileNodeConstants.ROOT_ID, "report.pdf",
                ConflictStrategy.KEEP.getCode());

        assertEquals("report(2).pdf", resolution.finalName());
        assertNull(resolution.existingToReplace());
    }

    @Test
    void shouldReturnExistingToReplaceWhenSameNameAndOverwriteStrategy() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        FileNodeVo existing = upload(userWithSpace, "report.pdf");

        FileConflictResolver.ConflictResolution resolution = uploadConflictResolver.resolve(
                userWithSpace.user().getId(), FileNodeConstants.ROOT_ID, "report.pdf",
                ConflictStrategy.OVERWRITE.getCode());

        assertEquals("report.pdf", resolution.finalName());
        assertNotNull(resolution.existingToReplace());
        assertEquals(existing.getId(), resolution.existingToReplace().getId());
        assertEquals("file", resolution.existingToReplace().getType());
        assertFalse(resolution.skipped());
    }

    @Test
    void shouldParseStrategyCodeString() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        upload(userWithSpace, "a.txt");

        FileConflictResolver.ConflictResolution keep = uploadConflictResolver.resolve(
                userWithSpace.user().getId(), FileNodeConstants.ROOT_ID, "a.txt", "keep");
        assertEquals("a(1).txt", keep.finalName());

        FileConflictResolver.ConflictResolution overwrite = uploadConflictResolver.resolve(
                userWithSpace.user().getId(), FileNodeConstants.ROOT_ID, "a.txt", "overwrite");
        assertNotNull(overwrite.existingToReplace());
        assertEquals("a.txt", overwrite.finalName());
    }

    @Test
    void shouldDelegateDeleteExistingForOverwrite() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        FileNodeVo uploaded = upload(userWithSpace, "report.pdf");
        FileNode node = fileMapper.selectById(uploaded.getId());
        User user = userMapper.selectById(userWithSpace.user().getId());
        assertEquals(14L, node.getSize());

        uploadConflictResolver.deleteExistingForOverwrite(node, user);

        assertNull(fileMapper.selectById(uploaded.getId()));
        assertEquals(0L, userMapper.selectById(user.getId()).getUsedSpace());
    }
}
