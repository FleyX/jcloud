package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.event.SyncCompletedEvent;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.service.MediaScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 同步后媒体库扫描触发组件（ADR 0020「同步后扫描」）。
 * <p>
 * 监听文件同步完成事件，为该用户所有媒体库提交扫描（同库重复提交由扫描侧合并，
 * 扫描完成后的级联非强制削刮为扫描链路内建行为，此处不重复触发）。
 * 异步执行，不阻塞同步任务本身；单个媒体库提交失败仅记日志，不影响其他库与同步结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaPostSyncScanSupport {

    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final MediaScanService mediaScanService;

    /**
     * 监听同步完成事件，异步触发该用户所有媒体库扫描。
     */
    @Async
    @EventListener
    public void onSyncCompleted(SyncCompletedEvent event) {
        triggerMediaScans(event.getUserId());
    }

    /**
     * 为该用户所有媒体库提交一次增量扫描。
     *
     * @param userId 用户 ID
     */
    public void triggerMediaScans(String userId) {
        List<MediaDirectory> directories = mediaDirectoryMapper.selectList(
                new LambdaQueryWrapper<MediaDirectory>().eq(MediaDirectory::getUserId, userId));
        if (directories.isEmpty()) {
            return;
        }
        log.info("文件同步完成，触发用户媒体库扫描: userId={}, 媒体库数={}", userId, directories.size());
        for (MediaDirectory directory : directories) {
            try {
                mediaScanService.submitScan(directory.getId(), userId);
            } catch (Exception e) {
                log.warn("媒体库扫描提交失败: directory={}, error={}", directory.getId(), e.getMessage());
            }
        }
    }
}
