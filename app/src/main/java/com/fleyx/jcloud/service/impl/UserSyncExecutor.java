package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.constant.StorageConstant;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.SyncTaskStatus;
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
import com.fleyx.jcloud.service.support.FileNodeSupport;
import com.fleyx.jcloud.service.support.SyncContext;
import com.fleyx.jcloud.service.support.SyncTaskSupport;
import com.fleyx.jcloud.service.support.UserSpaceSupport;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.IdUtil;
import com.fleyx.jcloud.util.UserReadWriteLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 用户存储空间同步任务执行器。
 * <p>
 * 监听同步任务提交事件，异步执行物理目录到数据库的增量对齐。
 * 默认以单条数据库操作自动提交为单位，不依赖大事务，以支持长时间同步与及时状态回写。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSyncExecutor {

    private static final String ERROR_LOCK_TIMEOUT = "获取用户写锁超时";

    private final UserSyncTaskMapper userSyncTaskMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final FileMapper fileMapper;
    private final UserReadWriteLock userReadWriteLock;
    private final UserSpaceSupport userSpaceSupport;
    private final FileNodeSupport fileNodeSupport;
    private final SyncTaskSupport syncTaskSupport;

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

    private void doSync(UserSyncTask task) {
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
            return;
        }

        syncFolder(rootNode, rootDir, context);
        syncTaskSupport.completeTask(task, context, userSyncTaskMapper);
    }

    private void syncFolder(FileNode parentNode, Path physicalFolder, SyncContext context) {
        List<FileNode> dbChildren = loadDbChildren(context.getUserId(), parentNode.getId());
        Map<String, FileNode> dbByName = new HashMap<>();
        for (FileNode child : dbChildren) {
            dbByName.put(child.getName(), child);
        }

        List<Path> physicalChildren = listPhysicalChildren(physicalFolder);
        Map<String, Path> physicalByName = new HashMap<>();
        for (Path child : physicalChildren) {
            physicalByName.put(child.getFileName().toString(), child);
        }

        // 删除物理端不存在的 DB 节点
        for (FileNode dbChild : dbChildren) {
            if (!physicalByName.containsKey(dbChild.getName())) {
                try {
                    fileNodeSupport.deleteSubtree(dbChild);
                    context.recordSuccess();
                } catch (Exception e) {
                    log.warn("删除数据库子树失败，nodeId={}", dbChild.getId(), e);
                    context.recordFailure("删除节点 " + dbChild.getName() + " 失败: " + e.getMessage());
                }
            }
        }

        // 处理物理端节点
        for (Path physicalChild : physicalChildren) {
            String name = physicalChild.getFileName().toString();
            FileNode dbChild = dbByName.get(name);
            try {
                processPhysicalEntry(parentNode, dbChild, physicalChild, context);
            } catch (Exception e) {
                log.warn("同步物理条目失败，path={}", physicalChild, e);
                context.recordFailure("同步 " + name + " 失败: " + e.getMessage());
            }
        }
    }

    private void processPhysicalEntry(FileNode parentNode, FileNode dbChild, Path physicalPath,
                                      SyncContext context) throws IOException {
        boolean isDirectory = Files.isDirectory(physicalPath);
        String type = isDirectory ? FileNodeConstants.TYPE_FOLDER : FileNodeConstants.TYPE_FILE;
        long size = isDirectory ? 0L : Files.size(physicalPath);
        long lastModified = Files.getLastModifiedTime(physicalPath).toMillis();

        if (dbChild == null) {
            FileNode newNode = createNode(parentNode, physicalPath, type, size, lastModified, context);
            fileMapper.insert(newNode);
            context.recordSuccess();
            if (isDirectory) {
                syncFolder(newNode, physicalPath, context);
            }
            return;
        }

        if (!type.equals(dbChild.getType())) {
            fileNodeSupport.deleteSubtree(dbChild);
            FileNode newNode = createNode(parentNode, physicalPath, type, size, lastModified, context);
            fileMapper.insert(newNode);
            context.recordSuccess();
            if (isDirectory) {
                syncFolder(newNode, physicalPath, context);
            }
            return;
        }

        if (FileNodeConstants.TYPE_FILE.equals(type)) {
            Long dbSize = dbChild.getSize() == null ? 0L : dbChild.getSize();
            Long dbModified = dbChild.getLastModified() == null ? 0L : dbChild.getLastModified();
            if (!dbSize.equals(size) || !dbModified.equals(lastModified)) {
                LambdaUpdateWrapper<FileNode> wrapper = new LambdaUpdateWrapper<>();
                wrapper.eq(FileNode::getId, dbChild.getId());
                wrapper.set(FileNode::getSize, size);
                wrapper.set(FileNode::getLastModified, lastModified);
                wrapper.set(FileNode::getMimeType, probeMimeType(physicalPath));
                wrapper.set(FileNode::getHash, null);
                wrapper.set(FileNode::getUpdateTime, LocalDateTime.now());
                fileMapper.update(wrapper);
            }
            context.recordSuccess();
        } else {
            Long dbModified = dbChild.getLastModified() == null ? 0L : dbChild.getLastModified();
            if (!dbModified.equals(lastModified)) {
                dbChild.setLastModified(lastModified);
                dbChild.setUpdateTime(LocalDateTime.now());
                fileMapper.updateById(dbChild);
            }
            context.recordSuccess();
            syncFolder(dbChild, physicalPath, context);
        }
    }

    private List<FileNode> loadDbChildren(String userId, String parentId) {
        return fileMapper.selectByParentId(userId, parentId);
    }

    private List<Path> listPhysicalChildren(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(folder)) {
            return stream
                    .filter(this::isSyncable)
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        } catch (IOException e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "读取目录失败: " + folder, e);
        }
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

    private FileNode createNode(FileNode parentNode, Path physicalPath, String type, long size,
                                long lastModified, SyncContext context) {
        FileNode node = new FileNode();
        node.setId(IdUtil.nextId());
        node.setUserId(context.getUserId());
        node.setParentId(parentNode.getId());
        node.setName(physicalPath.getFileName().toString());
        node.setType(type);
        node.setSize(size);
        node.setLastModified(lastModified);
        node.setStorageSpaceId(context.getTargetId());
        node.setPath(FilePathUtil.buildChildPath(parentNode));
        node.setMimeType(FileNodeConstants.TYPE_FILE.equals(type) ? probeMimeType(physicalPath) : null);
        node.setStatus(1);
        return node;
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
