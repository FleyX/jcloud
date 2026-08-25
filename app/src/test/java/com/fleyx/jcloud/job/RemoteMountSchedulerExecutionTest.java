package com.fleyx.jcloud.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.enums.SyncTaskStatus;
import com.fleyx.jcloud.common.enums.SyncTaskType;
import com.fleyx.jcloud.mapper.RemoteMountMapper;
import com.fleyx.jcloud.mapper.RemoteSyncTaskMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.po.RemoteSyncTask;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.util.IdUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 远程挂载定时同步执行链路测试（票据 02、03）：cron 到点触发后，任务应被异步执行器消费，
 * 从 PENDING 推进到终态，而不是永远卡在 PENDING；滞留超过 5 分钟的 PENDING 任务应被自愈重发。
 * <p>
 * 夹具挂载点没有对应 FileNode、也没有有效 WebDAV 配置，执行器必然走失败分支，
 * 终态只能是 FAILED；FAILED 正是「任务被消费并离开 PENDING」的证据。
 * <p>
 * 异步执行线程独立读库，本测试类不使用事务回滚（参照 {@code MediaPostSyncScanTest} /
 * {@code UserSyncSchedulerExecutionTest} 模式），创建的测试数据按 ID 登记，每个用例结束后删除清理。
 */
class RemoteMountSchedulerExecutionTest extends IntegrationTestBase {

    private static final String DAILY_CRON = "0 0 2 * * *";

    @Autowired
    private RemoteMountScheduler remoteMountScheduler;

    @Autowired
    private RemoteMountMapper remoteMountMapper;

    @Autowired
    private RemoteSyncTaskMapper remoteSyncTaskMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StorageSpaceMapper storageSpaceMapper;

    private final List<String> createdMountIds = new ArrayList<>();
    private final List<String> createdUserIds = new ArrayList<>();
    private final List<String> createdSpaceIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdMountIds.forEach(mountId -> {
            remoteSyncTaskMapper.delete(new LambdaQueryWrapper<RemoteSyncTask>()
                    .eq(RemoteSyncTask::getRemoteMountId, mountId));
            remoteMountMapper.deleteById(mountId);
        });
        createdMountIds.clear();
        createdUserIds.forEach(userMapper::deleteById);
        createdUserIds.clear();
        createdSpaceIds.forEach(storageSpaceMapper::deleteById);
        createdSpaceIds.clear();
    }

    /**
     * 到点的启用挂载点触发定时同步后，任务应在合理时间内离开 PENDING 并到达终态 FAILED
     * （夹具无 FileNode、无有效 WebDAV 配置，执行器必然失败），且任务类型为 scheduled。
     */
    @Test
    void shouldExecuteScheduledTaskToFailedAfterDueTrigger() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        createdUserIds.add(user.getId());
        createdSpaceIds.add(userWithSpace.space().getId());
        RemoteMount mount = insertMount(user.getId());

        remoteMountScheduler.scheduleDueSync();

        RemoteSyncTask latest = awaitFailedTask(mount.getId());
        assertNotNull(latest, "定时触发后应创建同步任务");
        assertEquals(SyncTaskType.SCHEDULED.getValue(), latest.getType());
        assertEquals(SyncTaskStatus.FAILED.getValue(), latest.getStatus(),
                "定时同步任务应在 10s 内被异步执行器消费并离开 PENDING 到达终态 FAILED，而不是卡在 PENDING");
    }

    /**
     * 票据 03 Slice 2：滞留超过 5 分钟的 PENDING 任务在调度扫描后被重新投递，
     * 最终离开 PENDING 到达终态 FAILED（夹具挂载点无 FileNode、无有效 WebDAV 配置，
     * 被消费必然 FAILED，绝不可能 COMPLETED）。
     */
    @Test
    void shouldRedispatchStalePendingTask() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        createdUserIds.add(userWithSpace.user().getId());
        createdSpaceIds.add(userWithSpace.space().getId());
        RemoteMount mount = insertMount(userWithSpace.user().getId());
        RemoteSyncTask task = insertPendingTask(mount.getId());
        // create_time 为 INSERT 自动填充，必须 insert 后显式改为 6 分钟前，模拟滞留超过 5 分钟
        remoteSyncTaskMapper.update(null, new LambdaUpdateWrapper<RemoteSyncTask>()
                .eq(RemoteSyncTask::getId, task.getId())
                .set(RemoteSyncTask::getCreateTime, LocalDateTime.now().minusMinutes(6)));

        remoteMountScheduler.redispatchStalePendingTasks();

        RemoteSyncTask finished = awaitTaskFailed(task.getId());
        assertNotNull(finished, "重发后任务记录应仍存在");
        assertEquals(SyncTaskStatus.FAILED.getValue(), finished.getStatus(),
                "滞留超过 5 分钟的 PENDING 任务应在 10s 内被重新投递并执行到终态 FAILED，而不是卡在 PENDING");
    }

    /**
     * 票据 03 Slice 2：滞留不足 5 分钟的 PENDING 任务不被重发，保持 PENDING 且不新建任务记录。
     */
    @Test
    void shouldNotRedispatchFreshPendingTask() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        createdUserIds.add(userWithSpace.user().getId());
        createdSpaceIds.add(userWithSpace.space().getId());
        RemoteMount mount = insertMount(userWithSpace.user().getId());
        RemoteSyncTask task = insertPendingTask(mount.getId());

        remoteMountScheduler.redispatchStalePendingTasks();

        Thread.sleep(2000);
        RemoteSyncTask current = remoteSyncTaskMapper.selectById(task.getId());
        assertNotNull(current, "任务记录应仍存在");
        assertEquals(SyncTaskStatus.PENDING.getValue(), current.getStatus(),
                "滞留不足 5 分钟的 PENDING 任务不应被重发，应保持 PENDING");
        assertEquals(1, countTasksByMountId(mount.getId()), "重发仅重投既有任务，不应新建任务记录");
    }

    /**
     * 轮询该任务直到 FAILED（100ms × 最多 100 次 = 10s 超时），超时返回最后一次观测值。
     */
    private RemoteSyncTask awaitTaskFailed(String taskId) throws Exception {
        RemoteSyncTask task = null;
        for (int i = 0; i < 100; i++) {
            task = remoteSyncTaskMapper.selectById(taskId);
            if (task != null && SyncTaskStatus.FAILED.getValue().equals(task.getStatus())) {
                return task;
            }
            Thread.sleep(100);
        }
        return task;
    }

    private RemoteSyncTask insertPendingTask(String remoteMountId) {
        RemoteSyncTask task = new RemoteSyncTask();
        task.setId(IdUtil.nextId());
        task.setRemoteMountId(remoteMountId);
        task.setType(SyncTaskType.SCHEDULED.getValue());
        task.setStatus(SyncTaskStatus.PENDING.getValue());
        task.setTotalCount(0L);
        task.setSuccessCount(0L);
        task.setFailCount(0L);
        remoteSyncTaskMapper.insert(task);
        return task;
    }

    private long countTasksByMountId(String remoteMountId) {
        return remoteSyncTaskMapper.selectCount(new LambdaQueryWrapper<RemoteSyncTask>()
                .eq(RemoteSyncTask::getRemoteMountId, remoteMountId));
    }

    /**
     * 轮询该挂载点最新任务直到 FAILED（100ms × 最多 100 次 = 10s 超时），超时返回最后一次观测值。
     */
    private RemoteSyncTask awaitFailedTask(String remoteMountId) throws Exception {
        RemoteSyncTask latest = null;
        for (int i = 0; i < 100; i++) {
            latest = remoteSyncTaskMapper.selectLatestByMountId(remoteMountId);
            if (latest != null && SyncTaskStatus.FAILED.getValue().equals(latest.getStatus())) {
                return latest;
            }
            Thread.sleep(100);
        }
        return latest;
    }

    private RemoteMount insertMount(String userId) {
        RemoteMount mount = new RemoteMount();
        mount.setId(IdUtil.nextId());
        mount.setUserId(userId);
        mount.setName("exec-due-mount-" + System.nanoTime());
        mount.setType("webdav");
        mount.setEnabled(1);
        mount.setCronExpr(DAILY_CRON);
        mount.setNextSyncTime(LocalDateTime.now().minusMinutes(1));
        remoteMountMapper.insert(mount);
        createdMountIds.add(mount.getId());
        return mount;
    }
}