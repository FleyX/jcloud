package com.fleyx.jcloud.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 * 定时同步调度执行链路测试（票据 01）：cron 到点触发后，任务应被异步执行器消费，
 * 从 PENDING 推进到终态，而不是永远卡在 PENDING。
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
