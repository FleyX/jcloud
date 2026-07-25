package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 文件移动/复制执行器测试。
 */
class FileOperationExecutorTest {

    /**
     * 最终名称与源名称一致时，newName 应为 null（未发生重命名），
     * 避免前端误报“已重命名为”。
     */
    @Test
    void successShouldReturnNullNewNameWhenNameUnchanged() {
        FileOperationExecutor.OperationOutcome outcome =
                FileOperationExecutor.OperationOutcome.success("node1", "big(1).bin", "big(1).bin");

        assertEquals(FileNodeConstants.STATUS_SUCCESS, outcome.status());
        assertEquals("node1", outcome.sourceId());
        assertEquals("big(1).bin", outcome.sourceName());
        assertNull(outcome.newName());
        assertEquals("node1", outcome.nodeId());
    }

    /**
     * 冲突保留策略导致名称变化时，newName 应返回最终名称。
     */
    @Test
    void successShouldReturnNewNameWhenRenamed() {
        FileOperationExecutor.OperationOutcome outcome =
                FileOperationExecutor.OperationOutcome.success("node2", "a.txt", "a(1).txt");

        assertEquals("a.txt", outcome.sourceName());
        assertEquals("a(1).txt", outcome.newName());
    }

    /**
     * 最终名称为 null 时，newName 应为 null。
     */
    @Test
    void successShouldReturnNullNewNameWhenFinalNameIsNull() {
        FileOperationExecutor.OperationOutcome outcome =
                FileOperationExecutor.OperationOutcome.success("node3", "a.txt", null);

        assertNull(outcome.newName());
    }
}
