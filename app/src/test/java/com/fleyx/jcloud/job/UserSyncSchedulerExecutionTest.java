package com.fleyx.jcloud.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.common.constant.StorageConstant;
import com.fleyx.jcloud.common.enums.SyncTaskStatus;
import com.fleyx.jcloud.common.enums.SyncTaskType;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserSyncConfigMapper;
import com.fleyx.jcloud.mapper.UserSyncTaskMapper;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.UserSyncConfig;
import com.fleyx.jcloud.model.po.UserSyncTask;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserSyncTaskVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.StorageSpaceService;
import com.fleyx.jcloud.service.UserService;
import com.fleyx.jcloud.service.UserSyncService;
import com.fleyx.jcloud.util.IdUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 定时同步调度执行链路测试（票据 01、03）：cron 到点触发后，任务应被异步执行器消费，
 * 从 PENDING 推进到终态，而不是永远卡在 PENDING；滞留超过 5 分钟的 PENDING 任务应被自愈重发。
 * <p>
 * 异步执行线程独立读库，本测试类不使用事务回滚（参照 {@code MediaPostSyncScanTest} 模式），
 * 创建的测试数据按 ID 登记，每个用例结束后物理删除。
 */
@SpringBootTest
@ActiveProfiles("test")
class UserSyncSchedulerExecutionTest {

    private static final String DAILY_CRON = "0 0 2 * * *";

    @Autowired
    private UserSyncScheduler userSyncScheduler;

    @Autowired
    private UserSyncService userSyncService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserSyncConfigMapper userSyncConfigMapper;

    @Autowired
    private UserSyncTaskMapper userSyncTaskMapper;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private StorageSpaceMapper storageSpaceMapper;

    @TempDir
    Path tempDir;

    private final List<String> createdUserIds = new ArrayList<>();
    private final List<String> createdSpaceIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdUserIds.forEach(id -> {
            userSyncConfigMapper.delete(new LambdaQueryWrapper<UserSyncConfig>().eq(UserSyncConfig::getUserId, id));
            userSyncTaskMapper.delete(new LambdaQueryWrapper<UserSyncTask>().eq(UserSyncTask::getUserId, id));
            userMapper.deleteById(id);
        });
        createdUserIds.clear();
        createdSpaceIds.forEach(storageSpaceMapper::deleteById);
        createdSpaceIds.clear();
    }

    /**
     * 到点的启用配置触发定时同步后，任务应在合理时间内离开 PENDING 并到达终态 COMPLETED
     * （空物理目录同步必然成功），且任务类型为 scheduled。
     */
    @Test
    void shouldExecuteScheduledTaskToCompletedAfterDueTrigger() throws Exception {
        UserVo user = prepareUserWithStorageSpace();
        UserSyncConfig config = new UserSyncConfig();
        config.setUserId(user.getId());
        config.setEnabled(1);
        config.setCronExpr(DAILY_CRON);
        config.setNextSyncTime(LocalDateTime.now().minusMinutes(1));
        userSyncConfigMapper.insert(config);

        userSyncScheduler.scheduleDueSync();

        UserSyncTaskVo latest = awaitCompletedTask(user.getId());
        assertNotNull(latest, "定时触发后应创建同步任务");
        assertEquals(SyncTaskType.SCHEDULED.getValue(), latest.getType());
        assertEquals(SyncTaskStatus.COMPLETED.getValue(), latest.getStatus(),
                "定时同步任务应在 10s 内被执行到终态 COMPLETED，而不是卡在 PENDING");
    }

    /**
     * 票据 03 Slice 1：滞留超过 5 分钟的 PENDING 任务在调度扫描后被重新投递，
     * 最终离开 PENDING 到达终态 COMPLETED（空物理目录同步必然成功）。
     */
    @Test
    void shouldRedispatchStalePendingTask() throws Exception {
        UserVo user = prepareUserWithStorageSpace();
        UserSyncTask task = insertPendingTask(user.getId());
        // create_time 为 INSERT 自动填充，必须 insert 后显式改为 6 分钟前，模拟滞留超过 5 分钟
        userSyncTaskMapper.update(null, new LambdaUpdateWrapper<UserSyncTask>()
                .eq(UserSyncTask::getId, task.getId())
                .set(UserSyncTask::getCreateTime, LocalDateTime.now().minusMinutes(6)));

        userSyncScheduler.redispatchStalePendingTasks();

        UserSyncTask finished = awaitTaskCompleted(task.getId());
        assertNotNull(finished, "重发后任务记录应仍存在");
        assertEquals(SyncTaskStatus.COMPLETED.getValue(), finished.getStatus(),
                "滞留超过 5 分钟的 PENDING 任务应在 10s 内被重新投递并执行到终态 COMPLETED");
    }

    /**
     * 票据 03 Slice 1：滞留不足 5 分钟的 PENDING 任务不被重发，保持 PENDING 且不新建任务记录。
     */
    @Test
    void shouldNotRedispatchFreshPendingTask() throws Exception {
        UserVo user = prepareUserWithStorageSpace();
        UserSyncTask task = insertPendingTask(user.getId());

        userSyncScheduler.redispatchStalePendingTasks();

        Thread.sleep(2000);
        UserSyncTask current = userSyncTaskMapper.selectById(task.getId());
        assertNotNull(current, "任务记录应仍存在");
        assertEquals(SyncTaskStatus.PENDING.getValue(), current.getStatus(),
                "滞留不足 5 分钟的 PENDING 任务不应被重发，应保持 PENDING");
        assertEquals(1, countTasksByUserId(user.getId()), "重发仅重投既有任务，不应新建任务记录");
    }

    /**
     * 轮询该任务直到 COMPLETED（100ms × 最多 100 次 = 10s 超时），超时返回最后一次观测值。
     */
    private UserSyncTask awaitTaskCompleted(String taskId) throws Exception {
        UserSyncTask task = null;
        for (int i = 0; i < 100; i++) {
            task = userSyncTaskMapper.selectById(taskId);
            if (task != null && SyncTaskStatus.COMPLETED.getValue().equals(task.getStatus())) {
                return task;
            }
            Thread.sleep(100);
        }
        return task;
    }

    private UserSyncTask insertPendingTask(String userId) {
        UserSyncTask task = new UserSyncTask();
        task.setId(IdUtil.nextId());
        task.setUserId(userId);
        task.setType(SyncTaskType.SCHEDULED.getValue());
        task.setStatus(SyncTaskStatus.PENDING.getValue());
        task.setTotalCount(0L);
        task.setSuccessCount(0L);
        task.setFailCount(0L);
        userSyncTaskMapper.insert(task);
        return task;
    }

    private long countTasksByUserId(String userId) {
        return userSyncTaskMapper.selectCount(new LambdaQueryWrapper<UserSyncTask>()
                .eq(UserSyncTask::getUserId, userId));
    }

    /**
     * 轮询该用户最新任务直到 COMPLETED（100ms × 最多 100 次 = 10s 超时），超时返回最后一次观测值。
     */
    private UserSyncTaskVo awaitCompletedTask(String userId) throws Exception {
        UserSyncTaskVo latest = null;
        for (int i = 0; i < 100; i++) {
            latest = userSyncService.getLatestTask(userId);
            if (latest != null && SyncTaskStatus.COMPLETED.getValue().equals(latest.getStatus())) {
                return latest;
            }
            Thread.sleep(100);
        }
        return latest;
    }

    private UserVo prepareUserWithStorageSpace() throws Exception {
        Path spacePath = Files.createTempDirectory(tempDir, "scheduler-exec-space-");
        StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
        spaceDto.setName("scheduler-exec-space-" + System.nanoTime());
        spaceDto.setPath(spacePath.toString());
        StorageSpaceVo space = storageSpaceService.save(spaceDto);
        createdSpaceIds.add(space.getId());

        UserSaveDto userDto = new UserSaveDto();
        userDto.setUsername("sched_exec_user_" + System.nanoTime());
        userDto.setPassword("123456");
        userDto.setStorageSpaceId(space.getId());
        userDto.setQuota(1L);
        userDto.setQuotaUnit("GB");
        UserVo user = userService.saveUser(userDto);
        createdUserIds.add(user.getId());
        Files.createDirectories(spacePath.resolve(StorageConstant.FILES_DIR).resolve(user.getUsername()));
        return user;
    }
}
