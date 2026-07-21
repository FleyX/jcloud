package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 文件节点共享支撑组件测试。
 */
@ExtendWith(MockitoExtension.class)
class FileNodeSupportTest {

    @Mock
    private FileMapper fileMapper;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private FileNodeSupport fileNodeSupport;

    @Test
    void shouldRejectNullNodeId() {
        assertThrows(BusinessException.class, () -> fileNodeSupport.getOwnedNode(null, "u1"));
    }

    @Test
    void shouldRejectMissingNode() {
        when(fileMapper.selectById("n1")).thenReturn(null);

        assertThrows(BusinessException.class, () -> fileNodeSupport.getOwnedNode("n1", "u1"));
    }

    @Test
    void shouldRejectNodeOwnedByOtherUser() {
        FileNode node = new FileNode();
        node.setId("n1");
        node.setUserId("u2");
        when(fileMapper.selectById("n1")).thenReturn(node);

        assertThrows(BusinessException.class, () -> fileNodeSupport.getOwnedNode("n1", "u1"));
    }

    @Test
    void shouldReturnOwnedNode() {
        FileNode node = new FileNode();
        node.setId("n1");
        node.setUserId("u1");
        when(fileMapper.selectById("n1")).thenReturn(node);

        assertSame(node, fileNodeSupport.getOwnedNode("n1", "u1"));
    }

    @Test
    void shouldReturnNullWhenNoExistingChild() {
        when(fileMapper.selectList(any())).thenReturn(List.of());

        assertNull(fileNodeSupport.findExistingChild("u1", FileNodeConstants.ROOT_ID, "a.txt"));
    }

    @Test
    void shouldReturnExistingChild() {
        FileNode existing = new FileNode();
        existing.setId("n1");
        existing.setName("a.txt");
        when(fileMapper.selectList(any())).thenReturn(List.of(existing));

        assertSame(existing, fileNodeSupport.findExistingChild("u1", FileNodeConstants.ROOT_ID, "a.txt"));
    }

    @Test
    void shouldBuildFolderNodeWithResolvedStorageSpace() {
        User user = new User();
        user.setId("u1");
        user.setStorageSpaceId("s1");
        when(userMapper.selectById("u1")).thenReturn(user);

        FileNode folder = fileNodeSupport.buildFolderNode("u1", FileNodeConstants.ROOT_ID, "docs");

        assertEquals("u1", folder.getUserId());
        assertEquals(FileNodeConstants.ROOT_ID, folder.getParentId());
        assertEquals("docs", folder.getName());
        assertEquals("folder", folder.getType());
        assertEquals(0L, folder.getSize());
        assertEquals("s1", folder.getStorageSpaceId());
        assertEquals(FileNodeConstants.SOURCE_LOCAL, folder.getSourceType());
        assertEquals(1, folder.getStatus());
    }

    @Test
    void shouldRejectBuildFolderNodeWhenUserHasNoStorageSpace() {
        User user = new User();
        user.setId("u1");
        when(userMapper.selectById("u1")).thenReturn(user);

        assertThrows(BusinessException.class,
                () -> fileNodeSupport.buildFolderNode("u1", FileNodeConstants.ROOT_ID, "docs"));
    }

    @Test
    void shouldBuildFileNode() {
        FileNode node = fileNodeSupport.buildFileNode("u1", "p1", "a.txt", 10L, "hash", "s1", "text/plain");

        assertEquals("u1", node.getUserId());
        assertEquals("p1", node.getParentId());
        assertEquals("a.txt", node.getName());
        assertEquals("file", node.getType());
        assertEquals(10L, node.getSize());
        assertEquals("hash", node.getHash());
        assertEquals("s1", node.getStorageSpaceId());
        assertEquals(FileNodeConstants.SOURCE_LOCAL, node.getSourceType());
        assertEquals("text/plain", node.getMimeType());
        assertEquals(1, node.getStatus());
    }

    @Test
    void shouldSetRootPathWhenParentIsRoot() {
        FileNode node = new FileNode();

        fileNodeSupport.setNodePath(node, FileNodeConstants.ROOT_ID);

        assertEquals(FileNodeConstants.ROOT_ID, node.getPath());
    }

    @Test
    void shouldSetPathFromParent() {
        FileNode parent = new FileNode();
        parent.setId("p1");
        parent.setPath(FileNodeConstants.ROOT_ID);
        when(fileMapper.selectById("p1")).thenReturn(parent);
        FileNode node = new FileNode();

        fileNodeSupport.setNodePath(node, "p1");

        assertEquals(FileNodeConstants.ROOT_ID + FileNodeConstants.PATH_SEPARATOR + "p1", node.getPath());
    }

    @Test
    void shouldRejectSetNodePathWhenParentMissing() {
        when(fileMapper.selectById("p1")).thenReturn(null);

        assertThrows(BusinessException.class, () -> fileNodeSupport.setNodePath(new FileNode(), "p1"));
    }

    @Test
    void shouldResolveStorageSpaceId() {
        User user = new User();
        user.setId("u1");
        user.setStorageSpaceId("s1");
        when(userMapper.selectById("u1")).thenReturn(user);

        assertEquals("s1", fileNodeSupport.resolveStorageSpaceId("u1"));
    }

    @Test
    void shouldRejectResolveStorageSpaceIdWhenUserMissing() {
        when(userMapper.selectById("u1")).thenReturn(null);

        assertThrows(BusinessException.class, () -> fileNodeSupport.resolveStorageSpaceId("u1"));
    }
}
