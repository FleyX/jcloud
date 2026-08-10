package com.fleyx.jcloud.job;

import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.MediaScanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 媒体库定时重扫调度器测试。
 */
@Transactional
class MediaScanSchedulerTest extends IntegrationTestBase {

    @Autowired
    private MediaScanScheduler mediaScanScheduler;

    @Autowired
    private MediaDirectoryMapper mediaDirectoryMapper;

    @MockitoBean
    private MediaScanService mediaScanService;

    @Test
    void shouldSkipWhenNoDirectoryIsDue() {
        UserVo user = prepareUserWithStorageSpace().user();
        insertDirectory(user.getId(), null);
        insertDirectory(user.getId(), LocalDateTime.now().plusDays(1));

        mediaScanScheduler.scanDue();

        verifyNoInteractions(mediaScanService);
    }

    @Test
    void shouldSubmitScanForDueDirectory() {
        UserVo user = prepareUserWithStorageSpace().user();
        MediaDirectory due = insertDirectory(user.getId(), LocalDateTime.now().minusMinutes(1));

        mediaScanScheduler.scanDue();

        verify(mediaScanService).submitScan(due.getId(), user.getId());
    }

    @Test
    void shouldSubmitScanForAllDueDirectoriesAndSkipFutureOnes() {
        UserVo first = prepareUserWithStorageSpace().user();
        UserVo second = prepareUserWithStorageSpace().user();
        MediaDirectory dirA = insertDirectory(first.getId(), LocalDateTime.now().minusMinutes(5));
        MediaDirectory dirB = insertDirectory(first.getId(), LocalDateTime.now().minusSeconds(30));
        MediaDirectory dirC = insertDirectory(second.getId(), LocalDateTime.now().minusMinutes(1));
        MediaDirectory future = insertDirectory(second.getId(), LocalDateTime.now().plusDays(1));

        mediaScanScheduler.scanDue();

        verify(mediaScanService).submitScan(dirA.getId(), first.getId());
        verify(mediaScanService).submitScan(dirB.getId(), first.getId());
        verify(mediaScanService).submitScan(dirC.getId(), second.getId());
        verify(mediaScanService, never()).submitScan(future.getId(), second.getId());
    }

    @Test
    void shouldAbortRemainingDirectoriesWhenSubmitScanFails() {
        UserVo user = prepareUserWithStorageSpace().user();
        insertDirectory(user.getId(), LocalDateTime.now().minusMinutes(1));
        insertDirectory(user.getId(), LocalDateTime.now().minusMinutes(1));
        doThrow(new SystemException(ResultCode.SYSTEM_ERROR, "扫描任务提交失败", new RuntimeException("downstream")))
                .when(mediaScanService).submitScan(anyString(), anyString());

        assertThrows(SystemException.class, () -> mediaScanScheduler.scanDue());

        // 调度器无单目录容错：首个失败后整批中断，其余目录不再触发（与 RemoteMount/UserSync 调度器不同）
        verify(mediaScanService, times(1)).submitScan(anyString(), anyString());
    }

    private MediaDirectory insertDirectory(String userId, LocalDateTime nextScanTime) {
        MediaDirectory directory = new MediaDirectory();
        directory.setUserId(userId);
        directory.setName("测试媒体库-" + System.nanoTime());
        directory.setMediaType("movie");
        directory.setNextScanTime(nextScanTime);
        mediaDirectoryMapper.insert(directory);
        return directory;
    }
}
