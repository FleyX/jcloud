package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.dto.FileUploadPreCheckDto;
import com.fleyx.jcloud.model.dto.RemoteMountSaveDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.RemoteMountVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UploadPreCheckVo;
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 远程文件上传/下载服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RemoteFileServiceTest {

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
    void shouldUploadRemoteFile() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());

        FileNodeVo vo = remoteFileService.upload(buildFile("a.txt", "hello"), mountNode, user.getId(), "a.txt");

        assertNotNull(vo.getId());
        verify(adapter).upload(anyString(), any(), eq(5L), any());
        FileNode node = fileMapper.selectById(vo.getId());
        assertEquals("a.txt", node.getName());
        assertEquals("file", node.getType());
        assertEquals("remote", node.getSourceType());
        assertEquals(mountNode.getRemoteMountId(), node.getRemoteMountId());
        assertEquals(mountNode.getId(), node.getParentId());
    }

    @Test
    void shouldReplaceExistingRemoteFileOnUpload() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());
        FileNodeVo first = remoteFileService.upload(buildFile("a.txt", "v1"), mountNode, user.getId(), "a.txt");

        FileNodeVo second = remoteFileService.upload(buildFile("a.txt", "v2"), mountNode, user.getId(), "a.txt");

        assertNull(fileMapper.selectById(first.getId()));
        assertNotNull(fileMapper.selectById(second.getId()));
        assertEquals(1, fileMapper.selectByParentId(user.getId(), mountNode.getId()).size());
    }

    @Test
    void shouldRejectUploadOverFolder() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());
        remoteFileService.createFolder(mountNode, "dir", user.getId());

        assertThrows(BusinessException.class,
                () -> remoteFileService.upload(buildFile("dir", "x"), mountNode, user.getId(), "dir"));
    }

    @Test
    void shouldCreateRemoteFolder() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());

        FileNodeVo folder = remoteFileService.createFolder(mountNode, "docs", user.getId());

        assertNotNull(folder.getId());
        verify(adapter).createFolder(anyString());
        FileNode node = fileMapper.selectById(folder.getId());
        assertEquals("folder", node.getType());
        assertEquals("remote", node.getSourceType());

        assertThrows(BusinessException.class,
                () -> remoteFileService.createFolder(mountNode, "docs", user.getId()));
    }

    @Test
    void shouldDownloadRemoteFile() throws Exception {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());
        FileNodeVo uploaded = remoteFileService.upload(buildFile("a.txt", "hello"), mountNode, user.getId(), "a.txt");
        when(adapter.download(anyString())).thenReturn(new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));

        FileNode node = fileMapper.selectById(uploaded.getId());
        FileDownloadResult result = remoteFileService.download(node, user.getId());

        assertEquals("a.txt", result.getFileName());
        InputStream stream = result.getInputStream();
        assertNotNull(stream);
        assertEquals("data", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void shouldRejectDownloadLocalFile() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());
        FileNodeVo uploaded = remoteFileService.upload(buildFile("a.txt", "hello"), mountNode, user.getId(), "a.txt");
        FileNode node = fileMapper.selectById(uploaded.getId());
        node.setSourceType("local");

        assertThrows(BusinessException.class, () -> remoteFileService.download(node, user.getId()));
    }

    @Test
    void shouldRejectInstantUpload() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());

        assertThrows(BusinessException.class,
                () -> remoteFileService.instantUpload(null, mountNode, user.getId(), "a.txt"));
    }

    @Test
    void shouldPreCheckUploadConflict() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());
        remoteFileService.upload(buildFile("a.txt", "hello"), mountNode, user.getId(), "a.txt");

        FileUploadPreCheckDto dto = new FileUploadPreCheckDto();
        dto.setFileName("a.txt");
        UploadPreCheckVo conflict = remoteFileService.preCheckUpload(dto, mountNode, user.getId());
        assertEquals(1, conflict.getConflicts().size());

        dto.setFileName("b.txt");
        UploadPreCheckVo noConflict = remoteFileService.preCheckUpload(dto, mountNode, user.getId());
        assertTrue(noConflict.getConflicts().isEmpty());
    }

    private MockMultipartFile buildFile(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
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
            Path spacePath = Files.createTempDirectory(tempDir, "remote-file-space-");
            StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
            spaceDto.setName("remote-file-space-" + System.nanoTime());
            spaceDto.setPath(spacePath.toString());
            StorageSpaceVo space = storageSpaceService.save(spaceDto);

            UserSaveDto dto = new UserSaveDto();
            dto.setUsername("remote_file_user_" + System.nanoTime());
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
