package com.fleyx.jcloud.job;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.mapper.RemoteMountMapper;
import com.fleyx.jcloud.mapper.RemoteSyncTaskMapper;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.po.RemoteSyncTask;
import com.fleyx.jcloud.service.RemoteMountSyncService;
import com.fleyx.jcloud.service.support.SyncTaskSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 远程挂载定时同步调度器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoteMountScheduler {

    private final RemoteMountMapper remoteMountMapper;
    private final RemoteSyncTaskMapper remoteSyncTaskMapper;
    private final RemoteMountSyncService remoteMountSyncService;
    private final SyncTaskSupport syncTaskSupport;

    /**
     * 每分钟扫描一次到期的定时同步挂载点。
     */
    @Scheduled(fixedRate = 60_000)
    public void scheduleDueSync() {
        LocalDateTime now = LocalDateTime.now();
        List<RemoteMount> dueMounts = remoteMountMapper.selectEnabledDue(now);
        if (dueMounts.isEmpty()) {
            return;
        }
        log.info("扫描到 {} 个到期的远程挂载同步配置", dueMounts.size());
        for (RemoteMount mount : dueMounts) {
            try {
                triggerScheduledSync(mount, now);
            } catch (Exception e) {
                log.error("触发远程挂载定时同步失败，mountId={}", mount.getId(), e);
            }
        }
    }

    private void triggerScheduledSync(RemoteMount mount, LocalDateTime now) {
        String mountId = mount.getId();
        LocalDateTime nextSyncTime = calcNextSyncTime(mount, now);
        if (nextSyncTime == null) {
            // 解析失败或无未来执行时间：显式置空 next_sync_time，避免残留过期值导致每分钟重复创建任务
            log.warn("cron 解析失败或无未来执行时间，跳过本次定时同步并清除下次同步时间，mountId={}, cronExpr={}",
                    mountId, mount.getCronExpr());
            clearNextSyncTime(mountId);
            return;
        }

        if (hasRunningTask(mountId)) {
            log.info("挂载点存在进行中的同步任务，跳过本次定时同步，mountId={}", mountId);
            advanceNextSyncTime(mountId, nextSyncTime);
            return;
        }

        RemoteSyncTask task = remoteMountSyncService.createScheduledTask(mountId);
        advanceNextSyncTime(mountId, nextSyncTime);
        log.info("已触发远程挂载定时同步，mountId={}, taskId={}", mountId, task.getId());
    }

    private boolean hasRunningTask(String mountId) {
        return syncTaskSupport.hasActiveTask(remoteSyncTaskMapper, RemoteSyncTask::getRemoteMountId, mountId,
                RemoteSyncTask::getStatus, SyncTaskSupport.ACTIVE_STATUSES);
    }

    /**
     * 解析 cron 并计算下次同步时间；解析失败或无未来执行时间返回 {@code null}。
     */
    private LocalDateTime calcNextSyncTime(RemoteMount mount, LocalDateTime now) {
        CronExpression expression = syncTaskSupport.tryParseCron(mount.getCronExpr());
        return expression == null ? null : expression.next(now);
    }

    /**
     * 推进下次同步时间（用 LambdaUpdateWrapper 显式更新，规避 NOT_NULL 更新策略）。
     */
    private void advanceNextSyncTime(String mountId, LocalDateTime nextSyncTime) {
        LambdaUpdateWrapper<RemoteMount> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(RemoteMount::getId, mountId);
        wrapper.set(RemoteMount::getNextSyncTime, nextSyncTime);
        remoteMountMapper.update(wrapper);
    }

    /**
     * 显式把 next_sync_time 置空，避免残留过期值导致每分钟重复创建任务。
     */
    private void clearNextSyncTime(String mountId) {
        LambdaUpdateWrapper<RemoteMount> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(RemoteMount::getId, mountId);
        wrapper.set(RemoteMount::getNextSyncTime, null);
        remoteMountMapper.update(wrapper);
    }
}
