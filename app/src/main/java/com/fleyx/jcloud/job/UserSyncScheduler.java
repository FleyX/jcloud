package com.fleyx.jcloud.job;

import com.fleyx.jcloud.common.event.UserSyncSubmittedEvent;
import com.fleyx.jcloud.mapper.UserSyncConfigMapper;
import com.fleyx.jcloud.mapper.UserSyncTaskMapper;
import com.fleyx.jcloud.model.po.UserSyncConfig;
import com.fleyx.jcloud.model.po.UserSyncTask;
import com.fleyx.jcloud.service.UserSyncService;
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

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";

    private final UserSyncConfigMapper userSyncConfigMapper;
    private final UserSyncTaskMapper userSyncTaskMapper;
    private final UserSyncService userSyncService;
    private final ApplicationEventPublisher eventPublisher;

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
        if (hasRunningTask(userId)) {
            log.info("用户存在进行中的同步任务，跳过本次定时同步，userId={}", userId);
            updateNextSyncTime(config, now);
            return;
        }

        UserSyncTask task = userSyncService.createScheduledTask(userId);
        eventPublisher.publishEvent(new UserSyncSubmittedEvent(this, task.getId()));
        updateNextSyncTime(config, now);
        log.info("已触发用户定时同步，userId={}, taskId={}", userId, task.getId());
    }

    private boolean hasRunningTask(String userId) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserSyncTask> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.eq(UserSyncTask::getUserId, userId);
        wrapper.in(UserSyncTask::getStatus, List.of(STATUS_PENDING, STATUS_RUNNING));
        wrapper.eq(UserSyncTask::getDeleteAt, 0L);
        return userSyncTaskMapper.selectCount(wrapper) > 0;
    }

    private void updateNextSyncTime(UserSyncConfig config, LocalDateTime now) {
        try {
            CronExpression expression = CronExpression.parse(config.getCronExpr());
            LocalDateTime next = expression.next(now);
            config.setNextSyncTime(next);
            userSyncConfigMapper.updateById(config);
        } catch (Exception e) {
            log.warn("更新下次同步时间失败，userId={}", config.getUserId(), e);
        }
    }
}
