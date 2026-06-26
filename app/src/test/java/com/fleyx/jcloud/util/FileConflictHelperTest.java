package com.fleyx.jcloud.util;

import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.po.FileNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 文件冲突辅助类测试。
 */
@ExtendWith(MockitoExtension.class)
class FileConflictHelperTest {

    @Mock
    private FileMapper fileMapper;

    @Test
    void shouldReturnOriginalNameWhenNotOccupied() {
        Long userId = 1L;
        Long parentId = 0L;
        String originalName = "report.pdf";
        when(fileMapper.selectList(any())).thenReturn(List.of());

        String result = FileConflictHelper.generateKeepName(fileMapper, userId, parentId, originalName);

        assertEquals(originalName, result);
    }

    @Test
    void shouldGenerateKeepNameWithSuffixWhenNameOccupied() {
        Long userId = 1L;
        Long parentId = 0L;
        FileNode existing = buildNode("report.pdf");
        when(fileMapper.selectList(any())).thenReturn(List.of(existing));

        String result = FileConflictHelper.generateKeepName(fileMapper, userId, parentId, "report.pdf");

        assertEquals("report(1).pdf", result);
    }

    @Test
    void shouldGenerateKeepNameWithoutExt() {
        Long userId = 1L;
        Long parentId = 0L;
        FileNode existing = buildNode("README");
        when(fileMapper.selectList(any())).thenReturn(List.of(existing));

        String result = FileConflictHelper.generateKeepName(fileMapper, userId, parentId, "README");

        assertEquals("README(1)", result);
    }

    @Test
    void shouldSkipIntermediateGaps() {
        Long userId = 1L;
        Long parentId = 0L;
        FileNode existing1 = buildNode("report(1).pdf");
        FileNode existing3 = buildNode("report(3).pdf");
        when(fileMapper.selectList(any())).thenReturn(List.of(existing1, existing3));

        String result = FileConflictHelper.generateKeepName(fileMapper, userId, parentId, "report.pdf");

        assertEquals("report(4).pdf", result);
    }

    @Test
    void shouldDistinguishBaseName() {
        Long userId = 1L;
        Long parentId = 0L;
        FileNode other = buildNode("report2(1).pdf");
        when(fileMapper.selectList(any())).thenReturn(List.of(other));

        String result = FileConflictHelper.generateKeepName(fileMapper, userId, parentId, "report.pdf");

        assertEquals("report(1).pdf", result);
    }

    private FileNode buildNode(String name) {
        FileNode node = new FileNode();
        node.setName(name);
        return node;
    }
}
