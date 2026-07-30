package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体目录任务互斥组件测试。
 */
class MediaTaskSupportTest {

    private final MediaTaskSupport support = new MediaTaskSupport();

    @Test
    void shouldRejectSecondTaskOnSameDirectory() {
        assertTrue(support.enter("dir1", MediaTaskSupport.TASK_SCAN));
        assertFalse(support.enter("dir1", MediaTaskSupport.TASK_SCRAPE));
        assertThrows(BusinessException.class, () -> support.enterOrThrow("dir1", MediaTaskSupport.TASK_SCRAPE));
        // 不同目录互不阻塞
        assertTrue(support.enter("dir2", MediaTaskSupport.TASK_SCRAPE));
    }

    @Test
    void shouldReleaseOnlyOwnTask() {
        support.enter("dir1", MediaTaskSupport.TASK_SCAN);
        support.exit("dir1", MediaTaskSupport.TASK_SCRAPE);
        assertFalse(support.enter("dir1", MediaTaskSupport.TASK_SCRAPE));
        support.exit("dir1", MediaTaskSupport.TASK_SCAN);
        assertTrue(support.enter("dir1", MediaTaskSupport.TASK_SCRAPE));
    }

    @Test
    void shouldTrackCancelFlag() {
        assertFalse(support.isCancelled("dir1"));
        support.requestCancel("dir1");
        assertTrue(support.isCancelled("dir1"));
        support.clearCancel("dir1");
        assertFalse(support.isCancelled("dir1"));
        assertDoesNotThrow(() -> support.clearCancel("dir1"));
    }
}
