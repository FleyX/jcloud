package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.StorageConstant;
import com.fleyx.jcloud.common.enums.SyncTaskStatus;
import com.fleyx.jcloud.common.enums.SyncTaskType;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserSyncTaskMapper;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.po.UserSyncTask;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserSyncTaskVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.impl.UserSyncExecutor;
import com.fleyx.jcloud.util.IdUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 同步完成后触发媒体库扫描测试（ADR 0020「同步后扫描」）。
 * <p>
 * 监听器为异步执行且独立读库，本测试类不使用事务回滚（异步线程看不到未提交数据），
 * 测试数据按唯一用户名/媒体库隔离。事件可能由提交事件的 AFTER_COMMIT 异步执行与本测试
 * 显式执行各触发一次，故成功断言用 atLeastOnce。
 */
@SpringBootTest
@ActiveProfiles("test")
class MediaPostSyncScanTest {

    @Autowired
    private UserSyncExecutor userSyncExecutor;

    @Autowired
    private UserSyncService userSyncService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserSyncTaskMapper userSyncTaskMapper;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private MediaDirectoryMapper mediaDirectoryMapper;

    @Autowired
    private StorageSpaceMapper storageSpaceMapper;

    @MockitoBean
    private MediaScanService mediaScanService;

    /**
     * 本测试类不使用事务回滚（异步线程看不到未提交数据），创建的测试数据按 ID 登记，
     * 每个用例结束后物理删除，避免泄漏污染共享测试库（如存储空间分页计数）。
     */
    private final List<String> createdUserIds = new ArrayList<>();
    private final List<String> createdDirectoryIds = new ArrayList<>();
    private final List<String> createdSpaceIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdDirectoryIds.forEach(mediaDirectoryMapper::deleteById);
        createdDirectoryIds.clear();
        createdUserIds.forEach(id -> {
            userSyncTaskMapper.delete(new LambdaQueryWrapper<UserSyncTask>().eq(UserSyncTask::getUserId, id));
            userMapper.deleteById(id);
        });
        createdUserIds.clear();
        createdSpaceIds.forEach(storageSpaceMapper::deleteById);
        createdSpaceIds.clear();
    }

    /**
     * 同步成功（COMPLETED）后，该用户每个媒体库都收到扫描提交。
     */
    @Test
    void shouldTriggerMediaScanAfterSyncCompleted() throws Exception {
        UserVo user = prepareUserWithStorageSpace();
        MediaDirectory dirA = createDirectory(user.getId(), "电影库", "movie");
        MediaDirectory dirB = createDirectory(user.getId(), "电视库", "tv");

        UserSyncTaskVo task = userSyncService.submitImmediate(user.getId());
        userSyncExecutor.execute(task.getId());
        awaitTerminalStatus(user.getId(), "COMPLETED");

        verify(mediaScanService, timeout(5000).atLeastOnce()).submitScan(eq(dirA.getId()), eq(user.getId()));
        verify(mediaScanService, timeout(5000).atLeastOnce()).submitScan(eq(dirB.getId()), eq(user.getId()));
    }

    /**
     * 同步失败（FAILED）不触发媒体库扫描。
     */
    @Test
    void shouldNotTriggerMediaScanWhenSyncFailed() throws Exception {
        UserVo user = prepareUserWithStorageSpace();
        createDirectory(user.getId(), "电影库", "movie");
        // 破坏用户存储空间绑定，使同步执行失败
        User broken = new User();
        broken.setId(user.getId());
        broken.setStorageSpaceId("notexist00001");
        userMapper.updateById(broken);

        // submitImmediate 提交时即校验存储空间，故直接落库一个 PENDING 任务再显式执行
        UserSyncTask task = new UserSyncTask();
        task.setId(IdUtil.nextId());
        task.setUserId(user.getId());
        task.setType(SyncTaskType.MANUAL.getValue());
        task.setStatus(SyncTaskStatus.PENDING.getValue());
        task.setTotalCount(0L);
        task.setSuccessCount(0L);
        task.setFailCount(0L);
        userSyncTaskMapper.insert(task);

        userSyncExecutor.execute(task.getId());
        awaitTerminalStatus(user.getId(), "FAILED");

        verify(mediaScanService, after(1000).never()).submitScan(anyString(), eq(user.getId()));
    }

    /**
     * 同步成功但用户没有媒体库时无事发生。
     */
    @Test
    void shouldDoNothingWhenUserHasNoDirectory() throws Exception {
        UserVo user = prepareUserWithStorageSpace();

        UserSyncTaskVo task = userSyncService.submitImmediate(user.getId());
        userSyncExecutor.execute(task.getId());
        awaitTerminalStatus(user.getId(), "COMPLETED");

        verify(mediaScanService, after(1000).never()).submitScan(anyString(), eq(user.getId()));
    }

    /**
     * 等待最近一次同步任务进入期望的终态（异步 AFTER_COMMIT 执行与本测试显式执行存在竞争）。
     */
    private void awaitTerminalStatus(String userId, String expected) throws Exception {
        for (int i = 0; i < 50; i++) {
            UserSyncTaskVo latest = userSyncService.getLatestTask(userId);
            if (latest != null && expected.equals(latest.getStatus())) {
                return;
            }
            Thread.sleep(100);
        }
        assertEquals(expected, userSyncService.getLatestTask(userId).getStatus());
    }

    private MediaDirectory createDirectory(String userId, String name, String mediaType) {
        MediaDirectory directory = new MediaDirectory();
        directory.setUserId(userId);
        directory.setName(name);
        directory.setMediaType(mediaType);
        mediaDirectoryMapper.insert(directory);
        createdDirectoryIds.add(directory.getId());
        return directory;
    }

    private UserVo prepareUserWithStorageSpace() throws Exception {
        Path spacePath = Files.createTempDirectory("post-sync-scan-space-");
        StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
        spaceDto.setName("post-sync-scan-space-" + System.nanoTime());
        spaceDto.setPath(spacePath.toString());
        StorageSpaceVo space = storageSpaceService.save(spaceDto);
        createdSpaceIds.add(space.getId());

        UserSaveDto userDto = new UserSaveDto();
        userDto.setUsername("post_scan_user_" + System.nanoTime());
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
