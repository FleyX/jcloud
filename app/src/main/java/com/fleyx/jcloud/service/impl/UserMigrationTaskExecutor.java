package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.event.UserMigrationSubmittedEvent;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserMigrationTaskMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.po.UserMigrationTask;
import com.fleyx.jcloud.util.UserReadWriteLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.FileSystemUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 用户存储空间迁移任务执行器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserMigrationTaskExecutor {

    private final UserMigrationTaskMapper userMigrationTaskMapper;
    private final UserMapper userMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final FileMapper fileMapper;
    private final UserMigrationTransactionHelper transactionHelper;
    private final UserReadWriteLock userReadWriteLock;

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final long LOCK_WAIT_SECONDS = 30L;

    /**
     * 监听迁移任务提交事件，在事务提交后异步执行迁移。
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(UserMigrationSubmittedEvent event) {
        execute(event.getTaskId());
    }

    /**
     * 执行指定迁移任务。
     *
     * @param taskId 任务 ID
     */
    public void execute(Long taskId) {
        UserMigrationTask task = userMigrationTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("迁移任务不存在，taskId={}", taskId);
            return;
        }
        if (!STATUS_PENDING.equals(task.getStatus())) {
            log.info("迁移任务状态不是 PENDING，无需执行，taskId={}", taskId);
            return;
        }

        Long userId = task.getUserId();
        RLock lock = userReadWriteLock.writeLock(userId);
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("获取用户写锁超时，userId={}", userId);
                return;
            }
            doMigrate(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("迁移任务获取锁被中断，taskId={}", taskId);
        } catch (Exception e) {
            log.error("迁移任务执行失败，taskId={}", taskId, e);
            rollback(task, e.getMessage());
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    private void doMigrate(UserMigrationTask task) {
        Long userId = task.getUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        StorageSpace sourceSpace = storageSpaceMapper.selectById(task.getSourceSpaceId());
        StorageSpace targetSpace = storageSpaceMapper.selectById(task.getTargetSpaceId());
        if (sourceSpace == null || targetSpace == null) {
            throw new BusinessException("源或目标存储空间不存在");
        }

        long totalBytes = computeTotalBytes(userId);
        transactionHelper.markRunning(task.getId(), totalBytes);

        Path sourceDir = resolveUserFilesDir(sourceSpace, userId);
        Path targetDir = resolveUserFilesDir(targetSpace, userId);
        deleteIfExists(targetDir);

        long migratedBytes = 0L;
        if (Files.exists(sourceDir)) {
            List<Path> files;
            try (var stream = Files.walk(sourceDir)) {
                files = stream.filter(Files::isRegularFile).toList();
            } catch (Exception e) {
                throw new BusinessException("遍历源文件失败: " + e.getMessage());
            }
            for (Path sourceFile : files) {
                Path relative = sourceDir.relativize(sourceFile);
                Path targetFile = targetDir.resolve(relative);
                try {
                    Files.createDirectories(targetFile.getParent());
                    Files.copy(sourceFile, targetFile, StandardCopyOption.COPY_ATTRIBUTES);
                    migratedBytes += Files.size(sourceFile);
                } catch (Exception e) {
                    throw new BusinessException("复制文件失败: " + relative + ", " + e.getMessage());
                }
                transactionHelper.updateProgress(task.getId(), migratedBytes);
            }
        }

        transactionHelper.completeTask(task, userId, targetSpace.getId(), task.getNewQuota());
        log.info("迁移任务完成，taskId={}", task.getId());
    }

    private long computeTotalBytes(Long userId) {
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileNode::getUserId, userId);
        wrapper.eq(FileNode::getType, "file");
        List<FileNode> files = fileMapper.selectList(wrapper);
        return files.stream().mapToLong(f -> f.getSize() == null ? 0L : f.getSize()).sum();
    }

    private Path resolveUserFilesDir(StorageSpace space, Long userId) {
        return Path.of(space.getPath(), userId.toString(), "files");
    }

    private void deleteIfExists(Path path) {
        if (Files.exists(path)) {
            try {
                FileSystemUtils.deleteRecursively(path);
            } catch (Exception e) {
                log.warn("删除目标目录失败: {}", path, e);
            }
        }
    }

    private void rollback(UserMigrationTask task, String errorMsg) {
        transactionHelper.failTask(task, task.getUserId(), errorMsg);
        StorageSpace targetSpace = storageSpaceMapper.selectById(task.getTargetSpaceId());
        if (targetSpace != null) {
            Path targetDir = resolveUserFilesDir(targetSpace, task.getUserId());
            deleteIfExists(targetDir);
        }
        log.info("迁移任务已回滚，taskId={}", task.getId());
    }
}
