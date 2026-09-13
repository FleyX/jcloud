package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.service.MediaPlaybackService;
import com.fleyx.jcloud.service.RemoteFileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文件节点流式读取支撑组件测试：远程整段流、本地 Range 定界（起止/开尾/后缀）、
 * MIME 推导、物理路径解析（存储空间/用户/祖先名缓存）与异常路径
 * （存储空间不存在、物理文件丢失、非法 Range 包装为 SystemException）。
 */
class MediaFileStreamSupportTest {

    private final FileMapper fileMapper = mock(FileMapper.class);
    private final StorageSpaceMapper storageSpaceMapper = mock(StorageSpaceMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final RemoteFileService remoteFileService = mock(RemoteFileService.class);

    private final MediaFileStreamSupport support =
            new MediaFileStreamSupport(fileMapper, storageSpaceMapper, userMapper, remoteFileService);

    @TempDir
    Path tempDir;

    private FileNode localNode(String name) {
        FileNode node = new FileNode();
        node.setId("fn-1");
        node.setUserId("user-1");
        node.setName(name);
        node.setSize(1_024L);
        node.setMimeType("video/mp4");
        node.setSourceType(FileNodeConstants.SOURCE_LOCAL);
        node.setStorageSpaceId("space-1");
        return node;
    }

    /**
     * 装配本地物理路径解析链路：空间/用户/祖先名缓存均打桩，
     * 物理路径公式 = space.path / files / username / [祖先名路径/]name。
     */
    private Path preparePhysicalFile(FileNode node, String content) throws Exception {
        StorageSpace space = new StorageSpace();
        space.setId("space-1");
        space.setPath(tempDir.toString());
        when(storageSpaceMapper.selectById("space-1")).thenReturn(space);
        User user = new User();
        user.setId("user-1");
        user.setUsername("user-1");
        when(userMapper.selectById("user-1")).thenReturn(user);
        FileNode ancestor = new FileNode();
        ancestor.setId("dir-1");
        ancestor.setName("视频");
        when(fileMapper.selectBatchIds(anyList())).thenReturn(List.of(ancestor));
        // 目录 id 路径锚定祖先，物理文件落在 视频/ 子目录下
        node.setPath(FileNodeConstants.ROOT_ID + ".dir-1");
        Path physical = tempDir.resolve("files/user-1/视频").resolve(node.getName());
        Files.createDirectories(physical.getParent());
        Files.writeString(physical, content);
        return physical;
    }

    /**
     * 远程来源：不支持 Range，整段流式返回，rangeStart/rangeEnd 为 null，总大小取节点大小。
     */
    @Test
    void shouldStreamRemoteNodeWithoutRange() {
        FileNode node = localNode("movie.mkv");
        node.setSourceType(FileNodeConstants.SOURCE_REMOTE);
        node.setSize(10L);
        FileDownloadResult download = new FileDownloadResult(
                "movie.mkv", new ByteArrayInputStream(new byte[]{1, 2, 3}), "video/x-matroska", 10L);
        when(remoteFileService.download(node, "user-1")).thenReturn(download);

        MediaPlaybackService.MediaStreamResult result = support.streamNode(node, "bytes=0-1", "user-1");

        assertNull(result.rangeStart());
        assertNull(result.rangeEnd());
        assertEquals(10L, result.totalSize());
        assertEquals("movie.mkv", result.fileName());
        assertEquals(download, result.downloadResult());
        // 远程整段流不触碰本地路径解析链路
        verify(remoteFileService).download(node, "user-1");
        verifyNoInteractions(storageSpaceMapper, userMapper, fileMapper);
    }

    /**
     * 本地来源 + 起止 Range：定界读取，rangeStart/rangeEnd 与实际读取内容一致，MIME 按扩展名推导。
     */
    @Test
    void shouldStreamLocalNodeWithBoundedRange() throws Exception {
        FileNode node = localNode("movie.mp4");
        String content = "a".repeat(1_024);
        preparePhysicalFile(node, content);

        MediaPlaybackService.MediaStreamResult result = support.streamNode(node, "bytes=0-1023", "user-1");

        assertEquals(0L, result.rangeStart());
        assertEquals(1_023L, result.rangeEnd());
        assertEquals(1_024L, result.totalSize());
        assertEquals("video/mp4", result.downloadResult().getContentType());
        try (InputStream in = result.downloadResult().getInputStream()) {
            assertArrayEquals(content.getBytes(), in.readAllBytes());
        }
    }

    /**
     * 本地来源 + 开尾 Range（bytes=512-）：end 收敛到文件末尾，读取尾部 512 字节。
     */
    @Test
    void shouldStreamLocalNodeWithOpenEndedRange() throws Exception {
        FileNode node = localNode("movie.mp4");
        String content = "b".repeat(1_024);
        preparePhysicalFile(node, content);

        MediaPlaybackService.MediaStreamResult result = support.streamNode(node, "bytes=512-", "user-1");

        assertEquals(512L, result.rangeStart());
        assertEquals(1_023L, result.rangeEnd());
        try (InputStream in = result.downloadResult().getInputStream()) {
            assertArrayEquals(content.substring(512).getBytes(), in.readAllBytes());
        }
    }

    /**
     * 本地来源 + 后缀 Range（bytes=-100）：start 回算为 size-100，读取文件尾部 100 字节。
     */
    @Test
    void shouldStreamLocalNodeWithSuffixRange() throws Exception {
        FileNode node = localNode("movie.mp4");
        String content = "c".repeat(1_024);
        preparePhysicalFile(node, content);

        MediaPlaybackService.MediaStreamResult result = support.streamNode(node, "bytes=-100", "user-1");

        assertEquals(924L, result.rangeStart());
        assertEquals(1_023L, result.rangeEnd());
        try (InputStream in = result.downloadResult().getInputStream()) {
            assertArrayEquals(content.substring(924).getBytes(), in.readAllBytes());
        }
    }

    /**
     * 无 Range：整文件读取，rangeStart/rangeEnd 为 null。
     */
    @Test
    void shouldStreamLocalNodeWithoutRange() throws Exception {
        FileNode node = localNode("movie.webm");
        preparePhysicalFile(node, "webm-content");

        MediaPlaybackService.MediaStreamResult result = support.streamNode(node, null, "user-1");

        assertNull(result.rangeStart());
        assertNull(result.rangeEnd());
        assertEquals("video/webm", result.downloadResult().getContentType());
    }

    /**
     * 存储空间不存在：抛 NOT_FOUND，且不再触碰用户查询。
     */
    @Test
    void shouldRejectWhenStorageSpaceMissing() {
        FileNode node = localNode("movie.mp4");
        when(storageSpaceMapper.selectById("space-1")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> support.streamNode(node, null, "user-1"));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
        assertEquals("存储空间不存在", exception.getMessage());
        verifyNoInteractions(userMapper);
    }

    /**
     * 物理文件已丢失：抛 NOT_FOUND「文件已丢失」。
     */
    @Test
    void shouldRejectWhenPhysicalFileMissing() {
        FileNode node = localNode("movie.mp4");
        StorageSpace space = new StorageSpace();
        space.setId("space-1");
        space.setPath(tempDir.toString());
        when(storageSpaceMapper.selectById("space-1")).thenReturn(space);
        User user = new User();
        user.setUsername("user-1");
        when(userMapper.selectById("user-1")).thenReturn(user);
        // 不写物理文件，直接触发「文件已丢失」

        BusinessException exception = assertThrows(BusinessException.class,
                () -> support.streamNode(node, null, "user-1"));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
        assertEquals("文件已丢失", exception.getMessage());
    }

    /**
     * 非法 Range（非数字区间）：解析抛出的运行时异常包装为 SystemException「媒体流读取失败」。
     */
    @Test
    void shouldWrapMalformedRangeAsSystemException() throws Exception {
        FileNode node = localNode("movie.mp4");
        preparePhysicalFile(node, "content");

        SystemException exception = assertThrows(SystemException.class,
                () -> support.streamNode(node, "bytes=abc-", "user-1"));

        assertEquals("媒体流读取失败", exception.getMessage());
    }
}
