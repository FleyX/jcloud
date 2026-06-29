package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserMigrationTaskMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.po.UserMigrationTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 用户存储空间迁移任务数据库事务助手。
 */
@Component
@RequiredArgsConstructor
public class UserMigrationTransactionHelper {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final int ERROR_MSG_MAX_LENGTH = 2000;

    private final UserMigrationTaskMapper userMigrationTaskMapper;
    private final UserMapper userMapper;
    private final FileMapper fileMapper;

    /**
     * 将任务置为运行中，并初始化总字节数。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markRunning(String taskId, long totalBytes) {
        UserMigrationTask task = userMigrationTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(STATUS_RUNNING);
        task.setTotalBytes(totalBytes);
        task.setMigratedBytes(0L);
        task.setUpdateTime(LocalDateTime.now());
        userMigrationTaskMapper.updateById(task);
    }

    /**
     * 更新已迁移字节数。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateProgress(String taskId, long migratedBytes) {
        UserMigrationTask task = new UserMigrationTask();
        task.setId(taskId);
        task.setMigratedBytes(migratedBytes);
        task.setUpdateTime(LocalDateTime.now());
        userMigrationTaskMapper.updateById(task);
    }

    /**
     * 完成迁移：更新文件节点、用户存储空间与配额，并置任务完成。
     */
    @Transactional(rollbackFor = Exception.class)
    public void completeTask(UserMigrationTask task, String userId, String targetSpaceId, Long newQuota) {
        User user = userMapper.selectById(userId);
        if (user != null) {
            user.setStorageSpaceId(targetSpaceId);
            user.setQuota(newQuota);
            user.setReadOnly(0);
            user.setUpdateTime(LocalDateTime.now());
            userMapper.updateById(user);
        }

        LambdaUpdateWrapper<FileNode> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(FileNode::getUserId, userId);
        wrapper.set(FileNode::getStorageSpaceId, targetSpaceId);
        fileMapper.update(wrapper);

        task.setStatus(STATUS_COMPLETED);
        task.setMigratedBytes(task.getTotalBytes());
        task.setUpdateTime(LocalDateTime.now());
        userMigrationTaskMapper.updateById(task);
    }

    /**
     * 迁移失败：置任务失败，恢复用户可写状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public void failTask(UserMigrationTask task, String userId, String errorMsg) {
        User user = userMapper.selectById(userId);
        if (user != null) {
            user.setReadOnly(0);
            user.setUpdateTime(LocalDateTime.now());
            userMapper.updateById(user);
        }

        task.setStatus(STATUS_FAILED);
        task.setErrorMsg(truncateError(errorMsg));
        task.setUpdateTime(LocalDateTime.now());
        userMigrationTaskMapper.updateById(task);
    }

    private String truncateError(String errorMsg) {
        if (errorMsg == null) {
            return null;
        }
        return errorMsg.length() > ERROR_MSG_MAX_LENGTH
                ? errorMsg.substring(0, ERROR_MSG_MAX_LENGTH)
                : errorMsg;
    }
}
