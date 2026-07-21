package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.po.FileNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文件路径共享支撑组件测试。
 */
@ExtendWith(MockitoExtension.class)
class FilePathSupportTest {

    @Mock
    private FileMapper fileMapper;

    @InjectMocks
    private FilePathSupport filePathSupport;

    @Test
    void shouldReturnRootPathWhenNodeIsNull() {
        assertEquals("/", filePathSupport.resolveNamePath(null, "u1"));
    }

    @Test
    void shouldResolveNamePathForRootLevelNode() {
        FileNode node = new FileNode();
        node.setId("n1");
        node.setName("a.txt");
        node.setPath(FileNodeConstants.ROOT_ID);

        assertEquals("/a.txt", filePathSupport.resolveNamePath(node, "u1"));
        verifyNoInteractions(fileMapper);
    }

    @Test
    void shouldResolveNamePathWithAncestors() {
        FileNode ancestor = new FileNode();
        ancestor.setId("f1");
        ancestor.setUserId("u1");
        ancestor.setName("docs");
        when(fileMapper.selectBatchIds(any())).thenReturn(List.of(ancestor));

        FileNode node = new FileNode();
        node.setId("n1");
        node.setName("a.txt");
        node.setPath(FileNodeConstants.ROOT_ID + FileNodeConstants.PATH_SEPARATOR + "f1");

        assertEquals("/docs/a.txt", filePathSupport.resolveNamePath(node, "u1"));
    }

    @Test
    void shouldReturnEmptyMapWhenAncestorIdsEmpty() {
        Map<String, String> cache = filePathSupport.queryAncestorNames("u1", Set.of());

        assertTrue(cache.isEmpty());
        verifyNoInteractions(fileMapper);
    }

    @Test
    void shouldOnlyKeepCurrentUserAncestors() {
        FileNode own = new FileNode();
        own.setId("f1");
        own.setUserId("u1");
        own.setName("docs");
        FileNode foreign = new FileNode();
        foreign.setId("f2");
        foreign.setUserId("u2");
        foreign.setName("other");
        when(fileMapper.selectBatchIds(any())).thenReturn(List.of(own, foreign));

        Map<String, String> cache = filePathSupport.queryAncestorNames("u1", Set.of("f1", "f2"));

        assertEquals(Map.of("f1", "docs"), cache);
    }
}
