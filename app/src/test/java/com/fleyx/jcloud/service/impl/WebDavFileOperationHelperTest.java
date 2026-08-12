package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.WebDavException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.service.support.FileChangeEventSupport;
import com.fleyx.jcloud.service.support.FileNodeSupport;
import com.fleyx.jcloud.service.support.FilePathSupport;
import com.fleyx.jcloud.service.support.UserSpaceSupport;
import com.fleyx.jcloud.service.support.UserUsedSpaceSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebDAV 文件操作辅助类测试。
 */
@ExtendWith(MockitoExtension.class)
class WebDavFileOperationHelperTest {

    private static final String USER_ID = "u1";
    private static final String USERNAME = "tester";
    private static final String SPACE_ID = "sp1";

    @Mock
    private FileMapper fileMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private StorageSpaceMapper storageSpaceMapper;

    @Mock
    private UserSpaceSupport userSpaceSupport;

    @Mock
    private UserUsedSpaceSupport userUsedSpaceSupport;

    private FileChangeEventSupport fileChangeEventSupport;

    @TempDir
    Path tempDir;

    private WebDavFileOperationHelper helper;

    @BeforeEach
    void setUp() {
        FileNodeSupport fileNodeSupport = new FileNodeSupport(fileMapper, userMapper);
        FilePathSupport filePathSupport = new FilePathSupport(fileMapper);
        fileChangeEventSupport = mock(FileChangeEventSupport.class);
        helper = new WebDavFileOperationHelper(fileMapper, storageSpaceMapper,
                userSpaceSupport, userUsedSpaceSupport, fileNodeSupport, filePathSupport, fileChangeEventSupport);
    }

    private User buildUser(long usedSpace, long quota) {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername(USERNAME);
        user.setStorageSpaceId(SPACE_ID);
        user.setUsedSpace(usedSpace);
        user.setQuota(quota);
        return user;
    }

    private StorageSpace buildSpace() {
        StorageSpace space = new StorageSpace();
        space.setId(SPACE_ID);
        space.setPath(tempDir.toString());
        return space;
    }

    private FileNode buildFolder(String id, String path, String name) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setUserId(USER_ID);
        node.setParentId(FileNodeConstants.ROOT_ID);
        node.setName(name);
        node.setType(FileNodeConstants.TYPE_FOLDER);
        node.setPath(path);
        node.setSize(0L);
        node.setStorageSpaceId(SPACE_ID);
        return node;
    }

    private FileNode buildFile(String id, FileNode parent, String name, long size) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setUserId(USER_ID);
        node.setParentId(parent.getId());
        node.setName(name);
        node.setType(FileNodeConstants.TYPE_FILE);
        node.setPath(parent.getPath() + FileNodeConstants.PATH_SEPARATOR + parent.getId());
        node.setSize(size);
        node.setStorageSpaceId(SPACE_ID);
        return node;
    }

    @Test
    void shouldSetCorrectIdPathWhenCreateFolderUnderSubDirectory() {
        // D13 回归：父节点必须按 parentId 从库中解析，而不是恒定解析为根节点
        User user = buildUser(0, 0);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        FileNode parent = buildFolder("p1", FileNodeConstants.ROOT_ID, "docs");
        when(fileMapper.selectById("p1")).thenReturn(parent);

        helper.createFolder(USER_ID, "p1", "sub");

        ArgumentCaptor<FileNode> captor = ArgumentCaptor.forClass(FileNode.class);
        verify(fileMapper).insert(captor.capture());
        FileNode inserted = captor.getValue();
        assertEquals(FileNodeConstants.ROOT_ID + FileNodeConstants.PATH_SEPARATOR + "p1", inserted.getPath());
        assertEquals("sub", inserted.getName());
        assertEquals("p1", inserted.getParentId());
    }

    @Test
    void shouldSetRootIdPathWhenCreateFolderUnderRoot() {
        User user = buildUser(0, 0);
        when(userMapper.selectById(USER_ID)).thenReturn(user);

        helper.createFolder(USER_ID, FileNodeConstants.ROOT_ID, "top");

        ArgumentCaptor<FileNode> captor = ArgumentCaptor.forClass(FileNode.class);
        verify(fileMapper).insert(captor.capture());
        assertEquals(FileNodeConstants.ROOT_ID, captor.getValue().getPath());
    }

    @Test
    void shouldPersistFreedSpaceWhenDeleteFile() throws Exception {
        // D14 回归：删除后扣减的 usedSpace 必须持久化（经集中记账原子扣减）
        User user = buildUser(100, 0);
        when(userSpaceSupport.requireUser(USER_ID)).thenReturn(user);
        when(storageSpaceMapper.selectById(SPACE_ID)).thenReturn(buildSpace());
        FileNode root = buildFolder(FileNodeConstants.ROOT_ID, FileNodeConstants.ROOT_ID, "");
        FileNode file = buildFile("f1", root, "a.txt", 40);
        // 物理路径：{space}/files/{username}/a.txt
        Path realFile = tempDir.resolve("files").resolve(USERNAME).resolve("a.txt");
        Files.createDirectories(realFile.getParent());
        Files.writeString(realFile, "hello");

        helper.delete(USER_ID, file);

        assertFalse(Files.exists(realFile));
        verify(userUsedSpaceSupport).addUsedSpace(USER_ID, -40L);
        verify(fileMapper).physicalDeleteByIds(List.of("f1"));
    }

    @Test
    void shouldRejectMoveToSelf() {
        FileNode root = buildFolder(FileNodeConstants.ROOT_ID, FileNodeConstants.ROOT_ID, "");
        FileNode source = buildFolder("s1", FileNodeConstants.ROOT_ID, "dir");
        FileNode targetParent = buildFolder("s1", FileNodeConstants.ROOT_ID, "dir");

        BusinessException e = assertThrows(BusinessException.class,
                () -> helper.move(USER_ID, source, targetParent, "dir"));
        assertEquals("不能移动到自身内部", e.getMessage());
        verify(fileMapper, never()).updateById(any(FileNode.class));
    }

    @Test
    void shouldRejectUploadWhenQuotaExceeded() {
        User user = buildUser(90, 100);
        when(userSpaceSupport.requireUser(USER_ID)).thenReturn(user);
        when(userSpaceSupport.requireSpace(SPACE_ID)).thenReturn(buildSpace());
        FileNode root = buildFolder(FileNodeConstants.ROOT_ID, FileNodeConstants.ROOT_ID, "");
        HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);

        WebDavException e = assertThrows(WebDavException.class,
                () -> helper.upload(USER_ID, root, "big.bin", request, 20));
        assertEquals(507, e.getStatusCode());
    }
}
