package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.enums.SyncTaskStatus;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserMigrationTaskMapper;
import com.fleyx.jcloud.model.po.UserMigrationTask;
import com.fleyx.jcloud.service.impl.UserMigrationTaskExecutor;
import com.fleyx.jcloud.service.impl.UserMigrationTransactionHelper;
import com.fleyx.jcloud.util.UserReadWriteLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 用户存储空间迁移任务执行器锁失败路径测试。
 */
@ExtendWith(MockitoExtension.class)
class UserMigrationTaskExecutorLockTest {

    @Mock
    private UserMigrationTaskMapper userMigrationTaskMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private StorageSpaceMapper storageSpaceMapper;

    @Mock
    private FileMapper fileMapper;

    @Mock
    private UserMigrationTransactionHelper transactionHelper;

    @Mock
    private UserReadWriteLock userReadWriteLock;

    @Mock
    private RLock lock;

    @InjectMocks
    private UserMigrationTaskExecutor executor;

    @AfterEach
    void clearInterrupt() {
        // 清理中断标记，避免影响同线程其他测试
        Thread.interrupted();
    }

    @Test
    void shouldFailTaskWhenLockTimeout() throws InterruptedException {
        UserMigrationTask task = buildTask();
        when(userMigrationTaskMapper.selectById("t1")).thenReturn(task);
        when(userReadWriteLock.writeLock("u1")).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);

        executor.execute("t1");

        verify(transactionHelper).failTask(task, "u1", "获取用户写锁超时");
        verifyNoInteractions(fileMapper);
    }

    @Test
    void shouldFailTaskWhenLockInterrupted() throws InterruptedException {
        UserMigrationTask task = buildTask();
        when(userMigrationTaskMapper.selectById("t1")).thenReturn(task);
        when(userReadWriteLock.writeLock("u1")).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException("boom"));

        executor.execute("t1");

        verify(transactionHelper).failTask(task, "u1", "迁移任务被中断");
        verifyNoInteractions(fileMapper);
    }

    @Test
    void shouldSkipWhenTaskNotPending() {
        UserMigrationTask task = buildTask();
        task.setStatus(SyncTaskStatus.RUNNING.getValue());
        when(userMigrationTaskMapper.selectById("t1")).thenReturn(task);

        executor.execute("t1");

        verifyNoInteractions(transactionHelper, lock);
    }

    private UserMigrationTask buildTask() {
        UserMigrationTask task = new UserMigrationTask();
        task.setId("t1");
        task.setUserId("u1");
        task.setStatus(SyncTaskStatus.PENDING.getValue());
        return task;
    }
}
