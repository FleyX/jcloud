package com.fleyx.jcloud.util;

import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.service.FileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件冲突覆盖副作用处理器 FileConflictOverwriteHandler 集成测试。
 */
@Transactional
class FileConflictOverwriteHandlerTest extends IntegrationTestBase {

    @Autowired
    private FileConflictOverwriteHandler overwriteHandler;

    @Autowired
    private FileService fileService;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StorageSpaceMapper storageSpaceMapper;

    private FileNodeVo upload(UserWithSpace userWithSpace, String name, byte[] content) {
        MultipartFile file = new MockMultipartFile("file", name, "text/plain", content);
        return fileService.upload(file, userWithSpace.user().getId(), FileNodeConstants.ROOT_ID, null);
    }

    /**
     * 按 main 代码 FilePathUtil 的公式推导节点物理路径：space.path / files / username / namePath。
     */
    private Path physicalPathOf(UserWithSpace userWithSpace, FileNode node) {
        StorageSpace space = storageSpaceMapper.selectById(node.getStorageSpaceId());
        User user = userMapper.selectById(node.getUserId());
        return FilePathUtil.resolvePhysicalPath(node, FilePathUtil.contextOf(space, user.getUsername()));
    }

    @Test
    void shouldDeleteNodePhysicalFileAndReduceUsedSpace() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        byte[] content = "hello".getBytes();
        FileNodeVo uploaded = upload(userWithSpace, "report.pdf", content);
        User user = userMapper.selectById(userWithSpace.user().getId());
        assertEquals(content.length, user.getUsedSpace());

        FileNode node = fileMapper.selectById(uploaded.getId());
        Path physicalPath = physicalPathOf(userWithSpace, node);
        assertTrue(Files.exists(physicalPath));

        overwriteHandler.deleteExistingForOverwrite(node, user);

        assertNull(fileMapper.selectById(uploaded.getId()));
        assertFalse(Files.exists(physicalPath));
        assertEquals(0L, userMapper.selectById(user.getId()).getUsedSpace());
    }

    @Test
    void shouldFloorUsedSpaceAtZeroAcrossConsecutiveOverwrites() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        byte[] content = "12345".getBytes();
        FileNodeVo first = upload(userWithSpace, "a.txt", content);
        FileNodeVo second = upload(userWithSpace, "b.txt", content);
        User user = userMapper.selectById(userWithSpace.user().getId());
        user.setUsedSpace(1L);
        userMapper.updateById(user);

        overwriteHandler.deleteExistingForOverwrite(fileMapper.selectById(first.getId()), user);
        assertEquals(0L, userMapper.selectById(user.getId()).getUsedSpace());

        overwriteHandler.deleteExistingForOverwrite(fileMapper.selectById(second.getId()), user);
        assertEquals(0L, userMapper.selectById(user.getId()).getUsedSpace());
    }

    @Test
    void shouldProceedWhenPhysicalFileAlreadyRemovedExternally() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        FileNodeVo uploaded = upload(userWithSpace, "report.pdf", "hello".getBytes());
        User user = userMapper.selectById(userWithSpace.user().getId());

        FileNode node = fileMapper.selectById(uploaded.getId());
        Path physicalPath = physicalPathOf(userWithSpace, node);
        Files.delete(physicalPath);
        assertFalse(Files.exists(physicalPath));

        assertDoesNotThrow(() -> overwriteHandler.deleteExistingForOverwrite(node, user));

        assertNull(fileMapper.selectById(uploaded.getId()));
        assertEquals(0L, userMapper.selectById(user.getId()).getUsedSpace());
    }

    @Test
    void shouldThrowBusinessExceptionWithCauseWhenPhysicalDeleteFails() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        FileNodeVo uploaded = upload(userWithSpace, "report.pdf", "hello".getBytes());
        User user = userMapper.selectById(userWithSpace.user().getId());

        FileNode node = fileMapper.selectById(uploaded.getId());
        Path physicalPath = physicalPathOf(userWithSpace, node);
        Files.delete(physicalPath);
        Files.createDirectories(physicalPath);
        Files.writeString(physicalPath.resolve("child"), "x");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> overwriteHandler.deleteExistingForOverwrite(node, user));

        assertEquals(ResultCode.BUSINESS_ERROR, ex.getResultCode());
        assertEquals("删除旧文件失败", ex.getMessage());
        assertNotNull(ex.getCause());
    }
}
