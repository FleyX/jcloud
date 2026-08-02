package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.constant.StorageConstant;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.SyncTaskStatus;
import com.fleyx.jcloud.common.event.SyncCompletedEvent;
import com.fleyx.jcloud.common.event.UserSyncSubmittedEvent;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserSyncTaskMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.po.UserSyncTask;
import com.fleyx.jcloud.service.support.AbstractTreeSyncExecutor;
import com.fleyx.jcloud.service.support.FileNodeSupport;
import com.fleyx.jcloud.service.support.SyncContext;
import com.fleyx.jcloud.service.support.SyncTaskSupport;
import com.fleyx.jcloud.service.support.UserSpaceSupport;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.IdUtil;
import com.fleyx.jcloud.util.UserReadWriteLock;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 用户存储空间同步任务执行器。
 * <p>
 * 监听同步任务提交事件，异步执行物理目录到数据库的增量对齐。
 * 默认以单条数据库操作自动提交为单位，不依赖大事务，以支持长时间同步与及时状态回写。
 * 目录对齐流程由 {@link AbstractTreeSyncExecutor} 承载，本类仅保留物理侧差异实现。
 */
@Slf4j
@Component
public class UserSyncExecutor extends AbstractTreeSyncExecutor<Path, Path> {

    private static final String ERROR_LOCK_TIMEOUT = "获取用户写锁超时";

    private final UserSyncTaskMapper userSyncTaskMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final UserReadWriteLock userReadWriteLock;
    private final UserSpaceSupport userSpaceSupport;
    private final FileNodeSupport fileNodeSupport;
    private final SyncTaskSupport syncTaskSupport;
    private final ApplicationEventPublisher eventPublisher;

    public UserSyncExecutor(FileMapper fileMapper,
                            UserSyncTaskMapper userSyncTaskMapper,
                            StorageSpaceMapper storageSpaceMapper,
                            UserReadWriteLock userReadWriteLock,
                            UserSpaceSupport userSpaceSupport,
                            FileNodeSupport fileNodeSupport,
                            SyncTaskSupport syncTaskSupport,
                            ApplicationEventPublisher eventPublisher) {
        super(fileMapper);
        this.userSyncTaskMapper = userSyncTaskMapper;
        this.storageSpaceMapper = storageSpaceMapper;
        this.userReadWriteLock = userReadWriteLock;
        this.userSpaceSupport = userSpaceSupport;
        this.fileNodeSupport = fileNodeSupport;
        this.syncTaskSupport = syncTaskSupport;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 监听同步任务提交事件，在事务提交后异步执行。
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(UserSyncSubmittedEvent event) {
        execute(event.getTaskId());
    }

    /**
     * 执行指定同步任务。
     *
     * @param taskId 任务 ID
     */
    public void execute(String taskId) {
        UserSyncTask task = userSyncTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("同步任务不存在，taskId={}", taskId);
            return;
        }
        if (!SyncTaskStatus.PENDING.getValue().equals(task.getStatus())) {
            log.info("同步任务状态不是 PENDING，无需执行，taskId={}", taskId);
            return;
        }

        String userId = task.getUserId();
        RLock lock = userReadWriteLock.writeLock(userId);
        boolean locked = false;
        try {
            locked = lock.tryLock(SyncTaskSupport.LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("获取用户写锁超时，userId={}", userId);
                syncTaskSupport.failTask(task, ERROR_LOCK_TIMEOUT, userSyncTaskMapper);
                return;
            }
            doSync(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("同步任务获取锁被中断，taskId={}", taskId);
            syncTaskSupport.failTask(task, "同步任务被中断", userSyncTaskMapper);
        } catch (Exception e) {
            log.error("同步任务执行失败，taskId={}", taskId, e);
            syncTaskSupport.failTask(task, e.getMessage(), userSyncTaskMapper);
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    private void doSync(UserSyncTask task) throws Exception {
        String userId = task.getUserId();
        User user = userSpaceSupport.requireUser(userId);
        StorageSpace space = storageSpaceMapper.selectById(user.getStorageSpaceId());
        if (space == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户未绑定存储空间");
        }

        syncTaskSupport.markRunning(task, userSyncTaskMapper);
        Path rootDir = Path.of(space.getPath(), StorageConstant.FILES_DIR, user.getUsername());
        SyncContext context = new SyncContext(task.getId(), userId, space.getId());

        FileNode rootNode = new FileNode();
        rootNode.setId(FileNodeConstants.ROOT_ID);
        rootNode.setPath(FileNodeConstants.ROOT_ID);
        rootNode.setName("");
        rootNode.setType(FileNodeConstants.TYPE_FOLDER);

        if (!Files.exists(rootDir)) {
            log.info("用户物理文件目录不存在，清空数据库文件树，userId={}", userId);
            clearAllUserNodes(userId);
            syncTaskSupport.completeTask(task, context, userSyncTaskMapper);
            publishSyncCompleted(userId);
            return;
        }

        syncFolder(rootNode, rootDir, context);
        syncTaskSupport.completeTask(task, context, userSyncTaskMapper);
        publishSyncCompleted(userId);
    }

    /**
     * 同步完成（COMPLETED 或 PARTIAL）后发布事件，触发媒体库扫描等后续动作。
     */
    private void publishSyncCompleted(String userId) {
        eventPublisher.publishEvent(new SyncCompletedEvent(this, userId, SyncCompletedEvent.TYPE_USER));
    }

    @Override
    protected List<Path> listChildren(Path source, SyncContext context) {
        if (!Files.isDirectory(source)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(source)) {
            return stream
                    .filter(this::isSyncable)
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        } catch (IOException e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "读取目录失败: " + source, e);
        }
    }

    @Override
    protected boolean isFolder(Path entry) {
        return Files.isDirectory(entry);
    }

    @Override
    protected String nameOf(Path entry) {
        return entry.getFileName().toString();
    }

    @Override
    protected boolean metaChanged(FileNode dbNode, Path entry) throws IOException {
        long lastModified = Files.getLastModifiedTime(entry).toMillis();
        Long dbModified = dbNode.getLastModified() == null ? 0L : dbNode.getLastModified();
        if (Files.isDirectory(entry)) {
            return !dbModified.equals(lastModified);
        }
        Long dbSize = dbNode.getSize() == null ? 0L : dbNode.getSize();
        return !dbSize.equals(Files.size(entry)) || !dbModified.equals(lastModified);
    }

    @Override
    protected void applyMetaUpdate(FileNode dbNode, Path entry) throws IOException {
        long lastModified = Files.getLastModifiedTime(entry).toMillis();
        if (Files.isDirectory(entry)) {
            dbNode.setLastModified(lastModified);
            dbNode.setUpdateTime(LocalDateTime.now());
            fileMapper.updateById(dbNode);
            return;
        }
        LambdaUpdateWrapper<FileNode> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(FileNode::getId, dbNode.getId());
        wrapper.set(FileNode::getSize, Files.size(entry));
        wrapper.set(FileNode::getLastModified, lastModified);
        wrapper.set(FileNode::getMimeType, probeMimeType(entry));
        wrapper.set(FileNode::getHash, null);
        wrapper.set(FileNode::getUpdateTime, LocalDateTime.now());
        fileMapper.update(wrapper);
    }

    @Override
    protected FileNode createNode(FileNode parentNode, Path entry, SyncContext context) throws IOException {
        boolean isDirectory = Files.isDirectory(entry);
        FileNode node = new FileNode();
        node.setId(IdUtil.nextId());
        node.setUserId(context.getUserId());
        node.setParentId(parentNode.getId());
        node.setName(entry.getFileName().toString());
        node.setType(isDirectory ? FileNodeConstants.TYPE_FOLDER : FileNodeConstants.TYPE_FILE);
        node.setSize(isDirectory ? 0L : Files.size(entry));
        node.setLastModified(Files.getLastModifiedTime(entry).toMillis());
        node.setStorageSpaceId(context.getTargetId());
        node.setPath(FilePathUtil.buildChildPath(parentNode));
        node.setMimeType(isDirectory ? null : probeMimeType(entry));
        node.setStatus(1);
        return node;
    }

    @Override
    protected Path childSource(Path entry, Path source) {
        return entry;
    }

    @Override
    protected void deleteDbSubtree(FileNode node, SyncContext context) {
        fileNodeSupport.deleteSubtree(node);
    }

    @Override
    protected void recordDeleteFailure(FileNode dbChild, Exception e, SyncContext context) {
        log.warn("删除数据库子树失败，nodeId={}", dbChild.getId(), e);
        context.recordFailure("删除节点 " + dbChild.getName() + " 失败: " + e.getMessage());
    }

    @Override
    protected void recordEntryFailure(Path entry, Exception e, SyncContext context) {
        log.warn("同步物理条目失败，path={}", entry, e);
        context.recordFailure("同步 " + entry.getFileName() + " 失败: " + e.getMessage());
    }

    private boolean isSyncable(Path path) {
        try {
            if (!Files.exists(path)) {
                return false;
            }
            String name = path.getFileName().toString();
            if (name.startsWith(".")) {
                return false;
            }
            if (Files.isSymbolicLink(path)) {
                return false;
            }
            if (!Files.isRegularFile(path) && !Files.isDirectory(path)) {
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("判断条目是否可同步时失败，path={}", path, e);
            return false;
        }
    }

    private String probeMimeType(Path path) {
        try {
            return Files.probeContentType(path);
        } catch (Exception e) {
            log.debug("探测 MIME 类型失败，path={}", path, e);
            return null;
        }
    }

    private void clearAllUserNodes(String userId) {
        fileMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getUserId, userId));
    }
}
