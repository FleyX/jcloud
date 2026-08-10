package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.RemoteMountMapper;
import com.fleyx.jcloud.model.dto.RemoteMountSaveDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.RemoteMountVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.impl.RemoteProtocolAdapterFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 远程文件组织操作服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RemoteFileOperationServiceTest {

    @Autowired
    private RemoteFileOperationService remoteFileOperationService;

    @Autowired
    private RemoteFileService remoteFileService;

    @Autowired
    private RemoteMountService remoteMountService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private RemoteMountMapper remoteMountMapper;

    @MockitoBean
    private RemoteProtocolAdapterFactory adapterFactory;

    @TempDir
    Path tempDir;

    private RemoteProtocolAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = Mockito.mock(RemoteProtocolAdapter.class);
        when(adapterFactory.create(any())).thenReturn(adapter);
    }

    @Test
    void shouldRenameMountPoint() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());

        remoteFileOperationService.rename(mountNode, "new-name", user.getId());

        assertEquals("new-name", fileMapper.selectById(mountNode.getId()).getName());
        RemoteMount mount = remoteMountMapper.selectById(mountNode.getRemoteMountId());
        assertEquals("new-name", mount.getName());
    }

    @Test
    void shouldRejectRenameMountPointWithIllegalName() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());

        assertThrows(BusinessException.class,
                () -> remoteFileOperationService.rename(mountNode, "a/b", user.getId()));
    }

    @Test
    void shouldRenameRemoteNode() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());
        FileNode node = uploadRemoteFile(mountNode, user.getId(), "a.txt");

        remoteFileOperationService.rename(node, "b.txt", user.getId());

        verify(adapter).move(anyString(), contains("b.txt"));
        assertEquals("b.txt", fileMapper.selectById(node.getId()).getName());
    }

    @Test
    void shouldRejectRenameRemoteNodeWhenConflict() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());
        uploadRemoteFile(mountNode, user.getId(), "a.txt");
        FileNode b = uploadRemoteFile(mountNode, user.getId(), "b.txt");

        assertThrows(BusinessException.class,
                () -> remoteFileOperationService.rename(b, "a.txt", user.getId()));
    }

    @Test
    void shouldMoveRemoteNode() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());
        FileNodeVo docs = remoteFileService.createFolder(mountNode, "docs", user.getId());
        FileNode node = uploadRemoteFile(mountNode, user.getId(), "a.txt");
        FileNode docsNode = fileMapper.selectById(docs.getId());

        remoteFileOperationService.move(node, docsNode, "a.txt", user.getId());

        verify(adapter).move(anyString(), contains("docs"));
        FileNode moved = fileMapper.selectById(node.getId());
        assertEquals(docs.getId(), moved.getParentId());
    }

    @Test
    void shouldRejectMoveMountPoint() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());

        assertThrows(BusinessException.class,
                () -> remoteFileOperationService.move(mountNode, mountNode, mountNode.getName(), user.getId()));
    }

    @Test
    void shouldDeleteRemoteNode() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());
        FileNode node = uploadRemoteFile(mountNode, user.getId(), "a.txt");

        remoteFileOperationService.delete(node, user.getId());

        verify(adapter).delete(anyString());
        assertNull(fileMapper.selectById(node.getId()));
    }

    @Test
    void shouldDeleteMountPointViaMountService() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());

        remoteFileOperationService.delete(mountNode, user.getId());

        assertNull(fileMapper.selectById(mountNode.getId()));
        RemoteMount mount = remoteMountMapper.selectById(mountNode.getRemoteMountId());
        assertTrue(mount == null || mount.getDeleteAt() != 0L);
    }

    @Test
    void shouldRejectRemoteCopy() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());
        FileNode node = uploadRemoteFile(mountNode, user.getId(), "a.txt");

        assertThrows(BusinessException.class,
                () -> remoteFileOperationService.copy(node, mountNode, user.getId()));
    }

    private FileNode uploadRemoteFile(FileNode mountNode, String userId, String name) {
        FileNodeVo vo = remoteFileService.upload(
                new MockMultipartFile("file", name, "text/plain", "x".getBytes(StandardCharsets.UTF_8)),
                mountNode, userId, name);
        return fileMapper.selectById(vo.getId());
    }

    private FileNode createMountNode(String userId) {
        RemoteMountSaveDto dto = new RemoteMountSaveDto();
        dto.setName("mount-" + System.nanoTime());
        dto.setType("webdav");
        dto.setUrl("http://example.com/dav");
        dto.setUsername("user");
        dto.setPassword("pass");
        dto.setEnabled(0);
        RemoteMountVo mount = remoteMountService.save(dto, userId);
        return fileMapper.selectById(mount.getId());
    }

    private UserVo prepareUser() {
        try {
            Path spacePath = Files.createTempDirectory(tempDir, "remote-op-space-");
            StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
            spaceDto.setName("remote-op-space-" + System.nanoTime());
            spaceDto.setPath(spacePath.toString());
            StorageSpaceVo space = storageSpaceService.save(spaceDto);

            UserSaveDto dto = new UserSaveDto();
            dto.setUsername("remote_op_user_" + System.nanoTime());
            dto.setPassword("123456");
            dto.setStorageSpaceId(space.getId());
            dto.setQuota(1L);
            dto.setQuotaUnit("GB");
            return userService.saveUser(dto);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
