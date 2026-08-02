package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.event.SyncCompletedEvent;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.service.MediaScanService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 同步后媒体库扫描触发组件测试。
 */
class MediaPostSyncScanSupportTest {

    private final MediaDirectoryMapper mediaDirectoryMapper = mock(MediaDirectoryMapper.class);
    private final MediaScanService mediaScanService = mock(MediaScanService.class);
    private final MediaPostSyncScanSupport support = new MediaPostSyncScanSupport(mediaDirectoryMapper, mediaScanService);

    /**
     * 同步完成事件发布后，该用户每个媒体库都收到一次扫描提交。
     */
    @Test
    void shouldSubmitScanForEachDirectory() {
        when(mediaDirectoryMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(directory("dir-1"), directory("dir-2")));

        support.onSyncCompleted(new SyncCompletedEvent(this, "user-1", SyncCompletedEvent.TYPE_USER));

        verify(mediaScanService).submitScan("dir-1", "user-1");
        verify(mediaScanService).submitScan("dir-2", "user-1");
    }

    /**
     * 单个媒体库提交失败不影响其他媒体库。
     */
    @Test
    void shouldContinueWhenOneSubmitFails() {
        when(mediaDirectoryMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(directory("dir-1"), directory("dir-2")));
        doThrow(new RuntimeException("boom")).when(mediaScanService).submitScan("dir-1", "user-1");

        support.onSyncCompleted(new SyncCompletedEvent(this, "user-1", SyncCompletedEvent.TYPE_REMOTE_MOUNT));

        verify(mediaScanService).submitScan("dir-2", "user-1");
    }

    /**
     * 无媒体库的用户不触发任何扫描。
     */
    @Test
    void shouldDoNothingWhenNoDirectories() {
        when(mediaDirectoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        support.onSyncCompleted(new SyncCompletedEvent(this, "user-1", SyncCompletedEvent.TYPE_USER));

        verify(mediaScanService, never()).submitScan(any(), any());
    }

    private MediaDirectory directory(String id) {
        MediaDirectory directory = new MediaDirectory();
        directory.setId(id);
        return directory;
    }
}
