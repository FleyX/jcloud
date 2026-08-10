package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.TransferTaskStatus;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.TransferTaskMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.dto.FilePreCheckOperationDto;
import com.fleyx.jcloud.model.dto.OperationItemDto;
import com.fleyx.jcloud.model.dto.RemoteMountSaveDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.TransferTask;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.RemoteMountVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.TransferTaskVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.impl.RemoteProtocolAdapterFactory;
import com.fleyx.jcloud.service.impl.TransferTaskExecutor;
import com.fleyx.jcloud.service.support.FileNodeSupport;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跨来源传输服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransferServiceTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private TransferTaskExecutor transferTaskExecutor;

    @Autowired
    private TransferTaskMapper transferTaskMapper;

    @Autowired
    private RemoteMountService remoteMountService;

    @Autowired
    private RemoteFileService remoteFileService;

    @Autowired
    private FileOperationService fileOperationService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StorageSpaceMapper storageSpaceMapper;

    @Autowired
    private FileNodeSupport fileNodeSupport;

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
    void shouldTransferRemoteFileToLocalCopy() throws Exception {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());
        FileNode remoteNode = uploadRemoteFile(mountNode, user.getId(), "a.txt");
        when(adapter.download(anyString()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        TransferTaskVo task = transferService.createTransfer(
                buildDto(remoteNode.getId(), null), "copy", user.getId());
        assertEquals(TransferTaskStatus.PENDING.getValue(), task.getStatus());
        assertEquals(FileNodeConstants.SOURCE_REMOTE, task.getSourceType());
        assertEquals(FileNodeConstants.SOURCE_LOCAL, task.getTargetType());
        assertEquals(1L, task.getTotalCount());

        transferTaskExecutor.execute(task.getId());

        TransferTask done = transferTaskMapper.selectById(task.getId());
        assertEquals(TransferTaskStatus.COMPLETED.getValue(), done.getStatus());
        assertEquals(1L, done.getSuccessCount());
        List<FileNode> children = fileMapper.selectByParentId(user.getId(), FileNodeConstants.ROOT_ID);
        FileNode localCopy = children.stream()
                .filter(n -> "a.txt".equals(n.getName()) && FileNodeConstants.SOURCE_LOCAL.equals(n.getSourceType()))
                .findFirst().orElse(null);
        assertNotNull(localCopy);
        assertNotNull(localCopy.getHash());
        // 复制模式源节点保留
        assertNotNull(fileMapper.selectById(remoteNode.getId()));
    }

    @Test
    void shouldTransferLocalFileToRemoteMove() throws Exception {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());
        FileNode localNode = createLocalFile(user, "move-me.txt", "content");

        TransferTaskVo task = transferService.createTransfer(
                buildDto(localNode.getId(), mountNode.getId()), "move", user.getId());
        transferTaskExecutor.execute(task.getId());

        TransferTask done = transferTaskMapper.selectById(task.getId());
        assertEquals(TransferTaskStatus.COMPLETED.getValue(), done.getStatus());
        verify(adapter).upload(contains("move-me.txt"), any(), anyLong(), any());
        // 远程镜像节点已建立
        List<FileNode> remoteChildren = fileMapper.selectByParentId(user.getId(), mountNode.getId());
        assertEquals(1, remoteChildren.size());
        assertEquals("move-me.txt", remoteChildren.get(0).getName());
        // 移动模式本地源已删除（进回收站）
        assertNull(fileMapper.selectById(localNode.getId()));
    }

    @Test
    void shouldRejectSameSourceTransfer() {
        UserVo user = prepareUser();
        FileNode localNode = createLocalFile(user, "a.txt", "x");

        assertThrows(BusinessException.class,
                () -> transferService.createTransfer(buildDto(localNode.getId(), null), "copy", user.getId()));
    }

    @Test
    void shouldRejectMixedSources() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());
        FileNode remoteNode = uploadRemoteFile(mountNode, user.getId(), "r.txt");
        FileNode localNode = createLocalFile(user, "l.txt", "x");

        FileExecuteOperationDto dto = buildDto(remoteNode.getId(), null);
        OperationItemDto item = new OperationItemDto();
        item.setId(localNode.getId());
        dto.getItems().add(item);
        assertThrows(BusinessException.class,
                () -> transferService.createTransfer(dto, "copy", user.getId()));
    }

    @Test
    void shouldRejectMountPointAsSource() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());
        FileNode localNode = createLocalFile(user, "a.txt", "x");

        assertThrows(BusinessException.class,
                () -> transferService.createTransfer(buildDto(mountNode.getId(), localNode.getParentId()),
                        "copy", user.getId()));
    }

    @Test
    void shouldRejectWhenQuotaExceeded() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());
        FileNode remoteNode = uploadRemoteFile(mountNode, user.getId(), "a.txt");
        User po = userMapper.selectById(user.getId());
        po.setUsedSpace(po.getQuota());
        userMapper.updateById(po);

        assertThrows(BusinessException.class,
                () -> transferService.createTransfer(buildDto(remoteNode.getId(), null), "copy", user.getId()));
    }

    @Test
    void shouldCancelPendingTask() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());
        FileNode remoteNode = uploadRemoteFile(mountNode, user.getId(), "a.txt");

        TransferTaskVo task = transferService.createTransfer(
                buildDto(remoteNode.getId(), null), "copy", user.getId());
        transferService.cancel(task.getId(), user.getId());

        assertEquals(TransferTaskStatus.CANCELED.getValue(),
                transferTaskMapper.selectById(task.getId()).getStatus());
        // 已取消任务不会被执行
        transferTaskExecutor.execute(task.getId());
        assertEquals(0L, transferTaskMapper.selectById(task.getId()).getSuccessCount());
    }

    @Test
    void shouldAllowCrossSourcePrecheck() {
        UserVo user = prepareUser();
        FileNode mountNode = createMountNode(user.getId());
        FileNode remoteNode = uploadRemoteFile(mountNode, user.getId(), "a.txt");

        FilePreCheckOperationDto dto = new FilePreCheckOperationDto();
        dto.setTargetParentId(null);
        OperationItemDto item = new OperationItemDto();
        item.setId(remoteNode.getId());
        dto.setItems(List.of(item));
        // 跨来源预检不再报错，无冲突时返回空列表
        assertEquals(0, fileOperationService.preCheckOperation(dto, user.getId()).size());
    }

    private FileExecuteOperationDto buildDto(String nodeId, String targetParentId) {
        FileExecuteOperationDto dto = new FileExecuteOperationDto();
        dto.setTargetParentId(targetParentId);
        OperationItemDto item = new OperationItemDto();
        item.setId(nodeId);
        dto.setItems(new java.util.ArrayList<>(List.of(item)));
        return dto;
    }

    private FileNode uploadRemoteFile(FileNode mountNode, String userId, String name) {
        var vo = remoteFileService.upload(
                new MockMultipartFile("file", name, "text/plain", "x".getBytes(StandardCharsets.UTF_8)),
                mountNode, userId, name);
        return fileMapper.selectById(vo.getId());
    }

    private FileNode createLocalFile(UserVo user, String name, String content) {
        try {
            User po = userMapper.selectById(user.getId());
            String spacePath = storageSpaceMapper.selectById(po.getStorageSpaceId()).getPath();
            Path dir = Path.of(spacePath).resolve("files").resolve(po.getUsername());
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
            FileNode node = fileNodeSupport.buildFileNode(user.getId(), FileNodeConstants.ROOT_ID, name,
                    content.length(), "hash-" + name, po.getStorageSpaceId(), "text/plain");
            node.setPath(FileNodeConstants.ROOT_ID);
            fileMapper.insert(node);
            return node;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
            Path spacePath = Files.createTempDirectory(tempDir, "transfer-space-");
            StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
            spaceDto.setName("transfer-space-" + System.nanoTime());
            spaceDto.setPath(spacePath.toString());
            StorageSpaceVo space = storageSpaceService.save(spaceDto);

            UserSaveDto dto = new UserSaveDto();
            dto.setUsername("transfer_user_" + System.nanoTime());
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
