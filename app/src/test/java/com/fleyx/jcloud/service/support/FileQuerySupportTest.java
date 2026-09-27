package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.model.convert.FileConvert;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.service.RemoteFileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文件查询共享支撑组件测试。
 */
@ExtendWith(MockitoExtension.class)
class FileQuerySupportTest {

    @Mock
    private FileMapper fileMapper;

    @Mock
    private StorageSpaceMapper storageSpaceMapper;

    @Mock
    private FileConvert fileConvert;

    @Mock
    private RemoteFileService remoteFileService;

    @Mock
    private UserSpaceSupport userSpaceSupport;

    @Mock
    private FileNodeSupport fileNodeSupport;

    @Mock
    private FilePathSupport filePathSupport;

    @InjectMocks
    private FileQuerySupport fileQuerySupport;

    @Test
    void shouldReturnAllNodesForValidIds() {
        FileNode first = node("n1", "u1");
        FileNode second = node("n2", "u1");
        when(fileMapper.selectBatchIds(any())).thenReturn(List.of(first, second));
        when(fileConvert.poToVo(first)).thenReturn(vo("n1"));
        when(fileConvert.poToVo(second)).thenReturn(vo("n2"));

        List<FileNodeVo> result = fileQuerySupport.listByIds("u1", List.of("n1", "n2"));

        assertEquals(List.of("n1", "n2"), result.stream().map(FileNodeVo::getId).toList());
    }

    @Test
    void shouldSkipMissingIds() {
        FileNode existing = node("n1", "u1");
        when(fileMapper.selectBatchIds(any())).thenReturn(List.of(existing));
        when(fileConvert.poToVo(existing)).thenReturn(vo("n1"));

        List<FileNodeVo> result = fileQuerySupport.listByIds("u1", List.of("n1", "n2"));

        assertEquals(List.of("n1"), result.stream().map(FileNodeVo::getId).toList());
    }

    @Test
    void shouldFilterNodesOwnedByOtherUsers() {
        FileNode own = node("n1", "u1");
        FileNode foreign = node("n2", "u2");
        when(fileMapper.selectBatchIds(any())).thenReturn(List.of(own, foreign));
        when(fileConvert.poToVo(own)).thenReturn(vo("n1"));

        List<FileNodeVo> result = fileQuerySupport.listByIds("u1", List.of("n1", "n2"));

        assertEquals(List.of("n1"), result.stream().map(FileNodeVo::getId).toList());
    }

    @Test
    void shouldReturnEmptyWithoutQueryWhenIdsEmpty() {
        assertTrue(fileQuerySupport.listByIds("u1", List.of()).isEmpty());
        verifyNoInteractions(fileMapper);
    }

    private FileNode node(String id, String userId) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setUserId(userId);
        node.setName(id + ".txt");
        node.setType("file");
        return node;
    }

    private FileNodeVo vo(String id) {
        FileNodeVo vo = new FileNodeVo();
        vo.setId(id);
        vo.setName(id + ".txt");
        vo.setType("file");
        return vo;
    }
}
