package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.model.po.FileNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 媒体条目视图组装支撑组件单元测试。
 */
class MediaItemVoSupportTest {

    private FileMapper fileMapper;
    private MediaItemVoSupport support;

    @BeforeEach
    void setUp() {
        fileMapper = mock(FileMapper.class);
        support = new MediaItemVoSupport(fileMapper, mock(MediaMetadataMapper.class));
    }

    @Test
    void loadNodeVersionMapShouldIgnoreNullNodeIds() {
        // 详情接口的元数据可能无海报/背景图（节点 id 为 null），null 应被过滤而不是抛 NPE
        Map<String, Long> result = support.loadNodeVersionMap(Arrays.asList(null, null));

        assertTrue(result.isEmpty());
        verifyNoInteractions(fileMapper);
    }

    @Test
    void loadNodeVersionMapShouldLoadVersionsForNonNullIds() {
        FileNode node = new FileNode();
        node.setId("n1");
        node.setLastModified(123L);
        when(fileMapper.selectBatchIds(List.of("n1"))).thenReturn(List.of(node));

        Map<String, Long> result = support.loadNodeVersionMap(Arrays.asList("n1", null));

        assertEquals(123L, result.get("n1"));
    }
}
