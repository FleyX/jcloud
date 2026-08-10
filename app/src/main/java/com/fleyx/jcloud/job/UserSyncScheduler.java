package com.fleyx.jcloud.job;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.common.event.UserSyncSubmittedEvent;
import com.fleyx.jcloud.mapper.UserSyncConfigMapper;
import com.fleyx.jcloud.mapper.UserSyncTaskMapper;
import com.fleyx.jcloud.model.po.UserSyncConfig;
import com.fleyx.jcloud.model.po.UserSyncTask;
import com.fleyx.jcloud.service.UserSyncService;
import com.fleyx.jcloud.service.support.SyncTaskSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户存储空间定时同步调度器。
 * <p>
 * 每分钟扫描一次已启用且到达下次同步时间的配置，创建任务并异步执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSyncScheduler {

    private final UserSyncConfigMapper userSyncConfigMapper;
    private final UserSyncTaskMapper userSyncTaskMapper;
    private final UserSyncService userSyncService;
    private final ApplicationEventPublisher eventPublisher;
    private final SyncTaskSupport syncTaskSupport;

    /**
     * 每分钟扫描到期配置并触发同步。
     */
    @Scheduled(fixedRate = 60_000)
    public void scheduleDueSync() {
        LocalDateTime now = LocalDateTime.now();
        List<UserSyncConfig> dueConfigs = userSyncConfigMapper.selectEnabledDue(now);
        if (dueConfigs.isEmpty()) {
            return;
        }
        log.info("扫描到 {} 个到期的定时同步配置", dueConfigs.size());

        for (UserSyncConfig config : dueConfigs) {
            try {
                triggerScheduledSync(config, now);
            } catch (Exception e) {
                log.error("触发定时同步失败，userId={}", config.getUserId(), e);
            }
        }
    }

    private void triggerScheduledSync(UserSyncConfig config, LocalDateTime now) {
        String userId = config.getUserId();
        LocalDateTime nextSyncTime = calcNextSyncTime(config, now);
        if (nextSyncTime == null) {
            // 解析失败或无未来执行时间：显式置空 next_sync_time，避免残留过期值导致每分钟重复创建任务
            log.warn("cron 解析失败或无未来执行时间，跳过本次定时同步并清除下次同步时间，userId={}, cronExpr={}",
                    userId, config.getCronExpr());
            clearNextSyncTime(userId);
            return;
        }

        if (hasRunningTask(userId)) {
            log.info("用户存在进行中的同步任务，跳过本次定时同步，userId={}", userId);
            advanceNextSyncTime(userId, nextSyncTime);
            return;
        }

        UserSyncTask task = userSyncService.createScheduledTask(userId);
        eventPublisher.publishEvent(new UserSyncSubmittedEvent(this, task.getId()));
        advanceNextSyncTime(userId, nextSyncTime);
        log.info("已触发用户定时同步，userId={}, taskId={}", userId, task.getId());
    }

    private boolean hasRunningTask(String userId) {
        return syncTaskSupport.hasActiveTask(userSyncTaskMapper, UserSyncTask::getUserId, userId,
                UserSyncTask::getStatus, SyncTaskSupport.ACTIVE_STATUSES);
    }

    /**
     * 解析 cron 并计算下次同步时间；解析失败或无未来执行时间返回 {@code null}。
     */
    private LocalDateTime calcNextSyncTime(UserSyncConfig config, LocalDateTime now) {
        CronExpression expression = syncTaskSupport.tryParseCron(config.getCronExpr());
        return expression == null ? null : expression.next(now);
    }

    /**
     * 推进下次同步时间（用 LambdaUpdateWrapper 显式更新，规避 NOT_NULL 更新策略）。
     */
    private void advanceNextSyncTime(String userId, LocalDateTime nextSyncTime) {
        LambdaUpdateWrapper<UserSyncConfig> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(UserSyncConfig::getUserId, userId);
        wrapper.set(UserSyncConfig::getNextSyncTime, nextSyncTime);
        userSyncConfigMapper.update(wrapper);
    }

    /**
     * 显式把 next_sync_time 置空，避免残留过期值导致每分钟重复创建任务。
     */
    private void clearNextSyncTime(String userId) {
        LambdaUpdateWrapper<UserSyncConfig> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(UserSyncConfig::getUserId, userId);
        wrapper.set(UserSyncConfig::getNextSyncTime, null);
        userSyncConfigMapper.update(wrapper);
    }
}
