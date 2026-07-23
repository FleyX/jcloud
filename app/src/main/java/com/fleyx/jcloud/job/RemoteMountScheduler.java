package com.fleyx.jcloud.job;

import com.fleyx.jcloud.common.event.RemoteMountSubmittedEvent;
import com.fleyx.jcloud.mapper.RemoteMountMapper;
import com.fleyx.jcloud.mapper.RemoteSyncTaskMapper;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.po.RemoteSyncTask;
import com.fleyx.jcloud.service.RemoteMountSyncService;
import com.fleyx.jcloud.service.support.SyncTaskSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
    private final ApplicationEventPublisher eventPublisher;
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
        if (hasRunningTask(mountId)) {
            log.info("挂载点存在进行中的同步任务，跳过本次定时同步，mountId={}", mountId);
            updateNextSyncTime(mount, now);
            return;
        }
        RemoteSyncTask task = remoteMountSyncService.createScheduledTask(mountId);
        eventPublisher.publishEvent(new RemoteMountSubmittedEvent(this, task.getId()));
        updateNextSyncTime(mount, now);
        log.info("已触发远程挂载定时同步，mountId={}, taskId={}", mountId, task.getId());
    }

    private boolean hasRunningTask(String mountId) {
        return syncTaskSupport.hasActiveTask(remoteSyncTaskMapper, RemoteSyncTask::getRemoteMountId, mountId,
                RemoteSyncTask::getStatus, SyncTaskSupport.ACTIVE_STATUSES);
    }

    private void updateNextSyncTime(RemoteMount mount, LocalDateTime now) {
        if (!StringUtils.hasText(mount.getCronExpr())) {
            mount.setNextSyncTime(null);
            remoteMountMapper.updateById(mount);
            return;
        }
        CronExpression expression = syncTaskSupport.tryParseCron(mount.getCronExpr());
        if (expression == null) {
            return;
        }
        mount.setNextSyncTime(expression.next(now));
        remoteMountMapper.updateById(mount);
    }
}
