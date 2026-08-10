package com.fleyx.jcloud.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.SyncTaskStatus;
import com.fleyx.jcloud.common.enums.SyncTaskType;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.UserSyncConfigMapper;
import com.fleyx.jcloud.mapper.UserSyncTaskMapper;
import com.fleyx.jcloud.model.po.UserSyncConfig;
import com.fleyx.jcloud.model.po.UserSyncTask;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.UserSyncService;
import com.fleyx.jcloud.util.IdUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 用户存储空间定时同步调度器测试。
 */
@Transactional
class UserSyncSchedulerTest extends IntegrationTestBase {

    private static final String DAILY_CRON = "0 0 2 * * *";

    @Autowired
    private UserSyncScheduler userSyncScheduler;

    @Autowired
    private UserSyncConfigMapper userSyncConfigMapper;

    @Autowired
    private UserSyncTaskMapper userSyncTaskMapper;

    @MockitoSpyBean
    private UserSyncService userSyncService;

    @Test
    void shouldSkipWhenNoEnabledDueConfig() {
        UserVo disabled = prepareUserWithStorageSpace().user();
        insertConfig(disabled.getId(), 0, DAILY_CRON, LocalDateTime.now().minusMinutes(1));
        UserVo future = prepareUserWithStorageSpace().user();
        insertConfig(future.getId(), 1, DAILY_CRON, LocalDateTime.now().plusDays(1));

        userSyncScheduler.scheduleDueSync();

        verify(userSyncService, never()).createScheduledTask(any());
    }

    @Test
    void shouldTriggerScheduledSyncForDueConfig() {
        UserVo user = prepareUserWithStorageSpace().user();
        LocalDateTime dueTime = LocalDateTime.now().minusMinutes(1);
        insertConfig(user.getId(), 1, DAILY_CRON, dueTime);

        userSyncScheduler.scheduleDueSync();

        // 真实创建并落库一条 PENDING 定时任务
        UserSyncTask task = selectTaskByUserId(user.getId());
        assertNotNull(task);
        assertEquals(SyncTaskStatus.PENDING.getValue(), task.getStatus());
        assertEquals(SyncTaskType.SCHEDULED.getValue(), task.getType());
        assertEquals(user.getId(), task.getUserId());
        // 下次同步时间被推进到未来
        assertTrue(userSyncConfigMapper.selectById(user.getId()).getNextSyncTime().isAfter(dueTime));
    }

    @Test
    void shouldSkipTriggerWhenActiveTaskExistsButAdvanceNextSyncTime() {
        UserVo user = prepareUserWithStorageSpace().user();
        LocalDateTime dueTime = LocalDateTime.now().minusMinutes(1);
        insertConfig(user.getId(), 1, DAILY_CRON, dueTime);
        insertRunningTask(user.getId());

        userSyncScheduler.scheduleDueSync();

        // 存在 RUNNING 任务时跳过触发，不新建任务，但 nextSyncTime 仍被推进
        assertEquals(1, countTasksByUserId(user.getId()));
        verify(userSyncService, never()).createScheduledTask(user.getId());
        assertTrue(userSyncConfigMapper.selectById(user.getId()).getNextSyncTime().isAfter(dueTime));
    }

    @Test
    void shouldContinueWithOtherConfigsWhenOneFails() {
        UserVo failing = prepareUserWithStorageSpace().user();
        UserVo ok = prepareUserWithStorageSpace().user();
        LocalDateTime dueTime = LocalDateTime.now().minusMinutes(1);
        insertConfig(failing.getId(), 1, DAILY_CRON, dueTime);
        insertConfig(ok.getId(), 1, DAILY_CRON, dueTime);
        doThrow(new SystemException(ResultCode.SYSTEM_ERROR, "创建定时同步任务失败", new RuntimeException("downstream")))
                .when(userSyncService).createScheduledTask(failing.getId());

        assertDoesNotThrow(() -> userSyncScheduler.scheduleDueSync());

        // 单个配置失败不拖垮整批：失败配置无任务且 nextSyncTime 不变，其余配置正常触发
        assertNull(selectTaskByUserId(failing.getId()));
        assertNextSyncTimeUnchanged(dueTime, userSyncConfigMapper.selectById(failing.getId()).getNextSyncTime());
        assertNotNull(selectTaskByUserId(ok.getId()));
        assertTrue(userSyncConfigMapper.selectById(ok.getId()).getNextSyncTime().isAfter(dueTime));
    }

    @Test
    void shouldSkipTaskAndClearNextSyncTimeWhenCronInvalid() {
        UserVo user = prepareUserWithStorageSpace().user();
        LocalDateTime dueTime = LocalDateTime.now().minusMinutes(1);
        insertConfig(user.getId(), 1, "not-a-cron", dueTime);

        userSyncScheduler.scheduleDueSync();

        // 非法 cron：不建任务，且 next_sync_time 被显式置空，避免残留过期值导致每分钟重复创建任务
        assertNull(selectTaskByUserId(user.getId()));
        assertNull(userSyncConfigMapper.selectById(user.getId()).getNextSyncTime());
    }

    @Test
    void shouldSkipTaskAndClearNextSyncTimeWhenBoundedCronExpired() {
        UserVo user = prepareUserWithStorageSpace().user();
        LocalDateTime dueTime = LocalDateTime.now().minusMinutes(1);
        insertConfig(user.getId(), 1, "0 0 2 1 1 * 2025", dueTime);

        userSyncScheduler.scheduleDueSync();

        // 有界 cron 已无未来执行时间（next()=null）：同样不建任务并置空 next_sync_time
        assertNull(selectTaskByUserId(user.getId()));
        assertNull(userSyncConfigMapper.selectById(user.getId()).getNextSyncTime());
    }

    /** PG timestamp 为微秒精度且四舍五入，与 Java 侧纳秒值按毫秒截断后比较。 */
    private static void assertNextSyncTimeUnchanged(LocalDateTime expected, LocalDateTime actual) {
        assertEquals(expected.truncatedTo(ChronoUnit.MILLIS), actual.truncatedTo(ChronoUnit.MILLIS));
    }

    private void insertConfig(String userId, Integer enabled, String cronExpr, LocalDateTime nextSyncTime) {
        UserSyncConfig config = new UserSyncConfig();
        config.setUserId(userId);
        config.setEnabled(enabled);
        config.setCronExpr(cronExpr);
        config.setNextSyncTime(nextSyncTime);
        userSyncConfigMapper.insert(config);
    }

    private UserSyncTask insertRunningTask(String userId) {
        UserSyncTask task = new UserSyncTask();
        task.setId(IdUtil.nextId());
        task.setUserId(userId);
        task.setType(SyncTaskType.SCHEDULED.getValue());
        task.setStatus(SyncTaskStatus.RUNNING.getValue());
        task.setTotalCount(0L);
        task.setSuccessCount(0L);
        task.setFailCount(0L);
        userSyncTaskMapper.insert(task);
        return task;
    }

    private UserSyncTask selectTaskByUserId(String userId) {
        return userSyncTaskMapper.selectOne(new LambdaQueryWrapper<UserSyncTask>()
                .eq(UserSyncTask::getUserId, userId));
    }

    private long countTasksByUserId(String userId) {
        return userSyncTaskMapper.selectCount(new LambdaQueryWrapper<UserSyncTask>()
                .eq(UserSyncTask::getUserId, userId));
    }
}
