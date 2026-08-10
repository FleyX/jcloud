package com.fleyx.jcloud.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.SyncTaskStatus;
import com.fleyx.jcloud.common.enums.SyncTaskType;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.RemoteMountMapper;
import com.fleyx.jcloud.mapper.RemoteSyncTaskMapper;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.po.RemoteSyncTask;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.RemoteMountSyncService;
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
 * 远程挂载定时同步调度器测试。
 */
@Transactional
class RemoteMountSchedulerTest extends IntegrationTestBase {

    private static final String DAILY_CRON = "0 0 2 * * *";

    @Autowired
    private RemoteMountScheduler remoteMountScheduler;

    @Autowired
    private RemoteMountMapper remoteMountMapper;

    @Autowired
    private RemoteSyncTaskMapper remoteSyncTaskMapper;

    @MockitoSpyBean
    private RemoteMountSyncService remoteMountSyncService;

    @Test
    void shouldSkipWhenNoEnabledDueMount() {
        UserVo user = prepareUserWithStorageSpace().user();
        // 已到期但未启用：不参与
        insertMount(user.getId(), "disabled-due", 0, DAILY_CRON, LocalDateTime.now().minusMinutes(1));
        // 已启用但未到期：不参与
        insertMount(user.getId(), "enabled-future", 1, DAILY_CRON, LocalDateTime.now().plusDays(1));
        // 已启用但未配置 cron：不参与
        insertMount(user.getId(), "no-cron", 1, null, null);

        remoteMountScheduler.scheduleDueSync();

        verify(remoteMountSyncService, never()).createScheduledTask(any());
    }

    @Test
    void shouldTriggerScheduledSyncForDueMount() {
        UserVo user = prepareUserWithStorageSpace().user();
        LocalDateTime dueTime = LocalDateTime.now().minusMinutes(1);
        RemoteMount mount = insertMount(user.getId(), "due-mount", 1, DAILY_CRON, dueTime);

        remoteMountScheduler.scheduleDueSync();

        // 真实创建并落库一条 PENDING 定时任务
        RemoteSyncTask task = selectTaskByMountId(mount.getId());
        assertNotNull(task);
        assertEquals(SyncTaskStatus.PENDING.getValue(), task.getStatus());
        assertEquals(SyncTaskType.SCHEDULED.getValue(), task.getType());
        assertEquals(mount.getId(), task.getRemoteMountId());
        // 下次同步时间被推进到未来
        assertTrue(remoteMountMapper.selectById(mount.getId()).getNextSyncTime().isAfter(dueTime));
    }

    @Test
    void shouldSkipTriggerWhenActiveTaskExistsButAdvanceNextSyncTime() {
        UserVo user = prepareUserWithStorageSpace().user();
        LocalDateTime dueTime = LocalDateTime.now().minusMinutes(1);
        RemoteMount mount = insertMount(user.getId(), "busy-mount", 1, DAILY_CRON, dueTime);
        insertRunningTask(mount.getId());

        remoteMountScheduler.scheduleDueSync();

        // 存在 RUNNING 任务时跳过触发，不新建任务，但 nextSyncTime 仍被推进
        assertEquals(1, countTasksByMountId(mount.getId()));
        verify(remoteMountSyncService, never()).createScheduledTask(mount.getId());
        assertTrue(remoteMountMapper.selectById(mount.getId()).getNextSyncTime().isAfter(dueTime));
    }

    @Test
    void shouldContinueWithOtherMountsWhenOneFails() {
        UserVo user = prepareUserWithStorageSpace().user();
        LocalDateTime dueTime = LocalDateTime.now().minusMinutes(1);
        RemoteMount failing = insertMount(user.getId(), "failing-mount", 1, DAILY_CRON, dueTime);
        RemoteMount ok = insertMount(user.getId(), "ok-mount", 1, DAILY_CRON, dueTime);
        doThrow(new SystemException(ResultCode.SYSTEM_ERROR, "创建定时同步任务失败", new RuntimeException("downstream")))
                .when(remoteMountSyncService).createScheduledTask(failing.getId());

        assertDoesNotThrow(() -> remoteMountScheduler.scheduleDueSync());

        // 单个挂载点失败不拖垮整批：失败挂载点无任务且 nextSyncTime 不变，其余挂载点正常触发
        assertNull(selectTaskByMountId(failing.getId()));
        assertNextSyncTimeUnchanged(dueTime, remoteMountMapper.selectById(failing.getId()).getNextSyncTime());
        assertNotNull(selectTaskByMountId(ok.getId()));
        assertTrue(remoteMountMapper.selectById(ok.getId()).getNextSyncTime().isAfter(dueTime));
    }

    @Test
    void shouldSkipTaskAndClearNextSyncTimeWhenCronInvalid() {
        UserVo user = prepareUserWithStorageSpace().user();
        LocalDateTime dueTime = LocalDateTime.now().minusMinutes(1);
        RemoteMount mount = insertMount(user.getId(), "bad-cron-mount", 1, "not-a-cron", dueTime);

        remoteMountScheduler.scheduleDueSync();

        // 非法 cron：不建任务，且 next_sync_time 被显式置空，避免残留过期值导致每分钟重复创建任务
        assertNull(selectTaskByMountId(mount.getId()));
        assertNull(remoteMountMapper.selectById(mount.getId()).getNextSyncTime());
    }

    @Test
    void shouldSkipTaskAndClearNextSyncTimeWhenCronBlank() {
        UserVo user = prepareUserWithStorageSpace().user();
        LocalDateTime dueTime = LocalDateTime.now().minusMinutes(1);
        RemoteMount mount = insertMount(user.getId(), "blank-cron-mount", 1, "   ", dueTime);

        remoteMountScheduler.scheduleDueSync();

        // 空白 cron 解析失败：同样不建任务并置空 next_sync_time
        assertNull(selectTaskByMountId(mount.getId()));
        assertNull(remoteMountMapper.selectById(mount.getId()).getNextSyncTime());
    }

    @Test
    void shouldSkipTaskAndClearNextSyncTimeWhenBoundedCronExpired() {
        UserVo user = prepareUserWithStorageSpace().user();
        LocalDateTime dueTime = LocalDateTime.now().minusMinutes(1);
        RemoteMount mount = insertMount(user.getId(), "bounded-cron-mount", 1, "0 0 2 1 1 * 2025", dueTime);

        remoteMountScheduler.scheduleDueSync();

        // 有界 cron 已无未来执行时间（next()=null）：同样不建任务并置空 next_sync_time
        assertNull(selectTaskByMountId(mount.getId()));
        assertNull(remoteMountMapper.selectById(mount.getId()).getNextSyncTime());
    }

    /** PG timestamp 为微秒精度且四舍五入，与 Java 侧纳秒值按毫秒截断后比较。 */
    private static void assertNextSyncTimeUnchanged(LocalDateTime expected, LocalDateTime actual) {
        assertEquals(expected.truncatedTo(ChronoUnit.MILLIS), actual.truncatedTo(ChronoUnit.MILLIS));
    }

    private RemoteMount insertMount(String userId, String name, Integer enabled, String cronExpr,
                                    LocalDateTime nextSyncTime) {
        RemoteMount mount = new RemoteMount();
        mount.setId(IdUtil.nextId());
        mount.setUserId(userId);
        mount.setName(name + "-" + System.nanoTime());
        mount.setType("webdav");
        mount.setEnabled(enabled);
        mount.setCronExpr(cronExpr);
        mount.setNextSyncTime(nextSyncTime);
        remoteMountMapper.insert(mount);
        return mount;
    }

    private RemoteSyncTask insertRunningTask(String remoteMountId) {
        RemoteSyncTask task = new RemoteSyncTask();
        task.setId(IdUtil.nextId());
        task.setRemoteMountId(remoteMountId);
        task.setType(SyncTaskType.SCHEDULED.getValue());
        task.setStatus(SyncTaskStatus.RUNNING.getValue());
        task.setTotalCount(0L);
        task.setSuccessCount(0L);
        task.setFailCount(0L);
        remoteSyncTaskMapper.insert(task);
        return task;
    }

    private RemoteSyncTask selectTaskByMountId(String remoteMountId) {
        return remoteSyncTaskMapper.selectOne(new LambdaQueryWrapper<RemoteSyncTask>()
                .eq(RemoteSyncTask::getRemoteMountId, remoteMountId));
    }

    private long countTasksByMountId(String remoteMountId) {
        return remoteSyncTaskMapper.selectCount(new LambdaQueryWrapper<RemoteSyncTask>()
                .eq(RemoteSyncTask::getRemoteMountId, remoteMountId));
    }
}
