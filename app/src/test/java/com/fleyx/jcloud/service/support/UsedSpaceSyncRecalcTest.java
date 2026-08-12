package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.constant.StorageConstant;
import com.fleyx.jcloud.common.event.SyncCompletedEvent;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.RecycleRecordMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserSyncTaskMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.po.UserSyncTask;
import com.fleyx.jcloud.model.vo.UserSyncTaskVo;
import com.fleyx.jcloud.service.FileService;
import com.fleyx.jcloud.service.UserSyncService;
import com.fleyx.jcloud.service.impl.UserSyncExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 用户同步完成后已用空间重算测试（票据 04）。
 * <p>
 * 监听器为异步执行且独立读库，本类不使用事务回滚（异步线程看不到未提交数据），
 * 测试数据按唯一用户隔离并在用例结束后物理清理。
 * 同步任务可能由提交事件的 AFTER_COMMIT 异步执行与本测试显式执行各触发一次，
 * 事件监听为 {@code @Async}，因此断言一律使用轮询（100ms 间隔、10s 超时）。
 */
class UsedSpaceSyncRecalcTest extends IntegrationTestBase {

    private static final String TASK_COMPLETED = "COMPLETED";

    @Autowired
    private UserSyncExecutor userSyncExecutor;

    @Autowired
    private UserSyncService userSyncService;

    @Autowired
    private FileService fileService;

    @Autowired
    private UserUsedSpaceSupport userUsedSpaceSupport;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private RecycleRecordMapper recycleRecordMapper;

    @Autowired
    private StorageSpaceMapper storageSpaceMapper;

    @Autowired
    private UserSyncTaskMapper userSyncTaskMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private final List<String> createdUserIds = new ArrayList<>();
    private final List<String> createdSpaceIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String userId : createdUserIds) {
            userSyncTaskMapper.delete(new LambdaQueryWrapper<UserSyncTask>().eq(UserSyncTask::getUserId, userId));
            recycleRecordMapper.delete(new LambdaQueryWrapper<RecycleRecord>().eq(RecycleRecord::getUserId, userId));
            fileMapper.delete(new LambdaQueryWrapper<FileNode>().eq(FileNode::getUserId, userId));
            userMapper.deleteById(userId);
        }
        createdUserIds.clear();
        createdSpaceIds.forEach(storageSpaceMapper::deleteById);
        createdSpaceIds.clear();
    }

    /**
     * 同步导入：物理目录写入若干已知字节数文件，整轮同步完成后 used_space 收敛为
     * 文件大小之和，且与重算组件返回值一致（修复「同步导入后实际大小 > 已用空间」主线 bug）。
     */
    @Test
    void shouldRecalcUsedSpaceAfterSyncImport() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithFilesDir();
        String userId = userWithSpace.user().getId();
        Path filesDir = userWithSpace.spacePath().resolve(StorageConstant.FILES_DIR)
                .resolve(userWithSpace.user().getUsername());

        Files.writeString(filesDir.resolve("a.txt"), "hello");
        Files.createDirectories(filesDir.resolve("docs"));
        Files.writeString(filesDir.resolve("docs").resolve("b.md"), "world of files");

        long expected = Files.size(filesDir.resolve("a.txt")) + Files.size(filesDir.resolve("docs").resolve("b.md"));

        runSync(userId);

        pollUsedSpace(userId, expected);
        assertEquals(expected, userUsedSpaceSupport.recalcUsedSpace(userId));
    }

    /**
     * 同步删除：先上传（used_space 已有值），删除物理文件后再次同步，
     * used_space 收敛为重算值（下降方向）。
     */
    @Test
    void shouldRecalcUsedSpaceAfterSyncDeletion() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithFilesDir();
        String userId = userWithSpace.user().getId();
        Path filesDir = userWithSpace.spacePath().resolve(StorageConstant.FILES_DIR)
                .resolve(userWithSpace.user().getUsername());

        byte[] content = "old content".getBytes(StandardCharsets.UTF_8);
        fileService.upload(new MockMultipartFile("file", "old.txt", "text/plain", content),
                userId, FileNodeConstants.ROOT_ID, null);
        assertEquals(content.length, usedSpaceOf(userId), "上传后已用空间应等于文件大小");

        Files.deleteIfExists(filesDir.resolve("old.txt"));

        runSync(userId);

        pollUsedSpace(userId, 0L);
    }

    /**
     * 事件语义锁定：远程挂载同步事件不触发重算，used_space 保持脏值不变
     * （仅 TYPE_USER 同步触发，FAILED 不发布事件的语义由现有发布逻辑保证，本测试锁定分支行为）。
     */
    @Test
    void shouldNotRecalcOnRemoteMountSyncEvent() throws Exception {
        String userId = prepareUserWithFilesDir().user().getId();

        User dirty = new User();
        dirty.setId(userId);
        dirty.setUsedSpace(100L);
        userMapper.updateById(dirty);
        assertEquals(100L, usedSpaceOf(userId));

        eventPublisher.publishEvent(new SyncCompletedEvent(this, userId, SyncCompletedEvent.TYPE_REMOTE_MOUNT));

        Thread.sleep(1000);
        assertEquals(100L, usedSpaceOf(userId), "远程挂载同步事件不应触发已用空间重算");
    }

    // ---------- 工具方法 ----------

    private UserWithSpace prepareUserWithFilesDir() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        createdUserIds.add(userWithSpace.user().getId());
        createdSpaceIds.add(userWithSpace.space().getId());
        Path filesDir = userWithSpace.spacePath().resolve(StorageConstant.FILES_DIR)
                .resolve(userWithSpace.user().getUsername());
        Files.createDirectories(filesDir);
        return userWithSpace;
    }

    private void runSync(String userId) {
        UserSyncTaskVo task = userSyncService.submitImmediate(userId);
        userSyncExecutor.execute(task.getId());
        awaitSyncCompleted(userId);
    }

    /**
     * 等待最近一次同步任务进入 COMPLETED（异步 AFTER_COMMIT 执行与本测试显式执行存在竞争）。
     */
    private void awaitSyncCompleted(String userId) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            UserSyncTaskVo latest = userSyncService.getLatestTask(userId);
            if (latest != null && TASK_COMPLETED.equals(latest.getStatus())) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                fail("同步任务未在超时内完成: userId=" + userId);
            }
            sleepQuietly(100);
        }
    }

    /**
     * 轮询 used_space 至期望值：100ms 间隔，10s 超时。
     */
    private void pollUsedSpace(String userId, long expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        long actual = usedSpaceOf(userId);
        while (actual != expected) {
            if (System.currentTimeMillis() > deadline) {
                fail("已用空间未收敛: userId=" + userId + ", 期望=" + expected + ", 实际=" + actual);
            }
            sleepQuietly(100);
            actual = usedSpaceOf(userId);
        }
    }

    private long usedSpaceOf(String userId) {
        User user = userMapper.selectById(userId);
        return user == null || user.getUsedSpace() == null ? 0L : user.getUsedSpace();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }
}
