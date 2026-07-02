package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.RemoteSyncTaskStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.event.RemoteMountSubmittedEvent;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.PreviewFileMapper;
import com.fleyx.jcloud.mapper.RemoteMountMapper;
import com.fleyx.jcloud.mapper.RemoteSyncTaskMapper;
import com.fleyx.jcloud.model.bo.RemoteFileEntry;
import com.fleyx.jcloud.model.bo.WebDavConfig;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.PreviewFile;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.po.RemoteSyncTask;
import com.fleyx.jcloud.service.RemoteProtocolAdapter;
import com.fleyx.jcloud.util.IdUtil;
import com.fleyx.jcloud.util.RemoteConfigCrypto;
import com.fleyx.jcloud.util.RemoteMountLock;
import com.fleyx.jcloud.util.RemotePathUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URLConnection;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 远程挂载同步任务执行器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoteMountSyncExecutor {

    private static final String TYPE_FILE = "file";
    private static final String TYPE_FOLDER = "folder";
    private static final String SOURCE_REMOTE = "remote";
    private static final long LOCK_WAIT_SECONDS = 30L;
    private static final long MAX_ERROR_LENGTH = 4000;

    private final RemoteSyncTaskMapper remoteSyncTaskMapper;
    private final RemoteMountMapper remoteMountMapper;
    private final FileMapper fileMapper;
    private final PreviewFileMapper previewFileMapper;
    private final RemoteProtocolAdapterFactory adapterFactory;
    private final RemoteConfigCrypto remoteConfigCrypto;
    private final RemoteMountLock remoteMountLock;
    private final ObjectMapper objectMapper;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(RemoteMountSubmittedEvent event) {
        execute(event.getTaskId());
    }

    public void execute(String taskId) {
        RemoteSyncTask task = remoteSyncTaskMapper.selectById(taskId);
        if (task == null || !RemoteSyncTaskStatus.PENDING.getValue().equals(task.getStatus())) {
            return;
        }
        RemoteMount mount = remoteMountMapper.selectById(task.getRemoteMountId());
        if (mount == null || mount.getDeleteAt() != 0L) {
            failTask(task, "挂载配置不存在或已删除");
            return;
        }

        RLock lock = remoteMountLock.getLock(mount.getId());
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                failTask(task, "获取挂载点锁超时");
                return;
            }
            doSync(task, mount);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failTask(task, "同步任务被中断");
        } catch (Exception e) {
            log.error("远程挂载同步失败，taskId={}", taskId, e);
            failTask(task, e.getMessage());
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    private void doSync(RemoteSyncTask task, RemoteMount mount) {
        markRunning(task);
        RemoteProtocolAdapter adapter = adapterFactory.create(mount);
        FileNode mountNode = findMountNode(mount.getId(), mount.getUserId());
        if (mountNode == null) {
            failTask(task, "挂载点文件节点不存在");
            return;
        }
        WebDavConfig config = readConfig(mount.getConfig());
        String remoteRootPath = RemotePathUtil.buildRemotePath(config.getRootPath(), "/");
        RemoteSyncContext context = new RemoteSyncContext(task.getId(), mount.getUserId(), mount.getId());

        syncFolder(mountNode, remoteRootPath, adapter, context);
        completeTask(task, context);
        updateMountStatus(mount, context);
    }

    private void syncFolder(FileNode parentNode, String remotePath, RemoteProtocolAdapter adapter, RemoteSyncContext context) {
        List<RemoteFileEntry> remoteChildren;
        try {
            remoteChildren = adapter.listChildren(remotePath);
        } catch (Exception e) {
            context.addError("列出远程目录 " + remotePath + " 失败: " + e.getMessage());
            return;
        }

        List<FileNode> dbChildren = fileMapper.selectByParentId(context.getUserId(), parentNode.getId());
        Map<String, FileNode> dbByName = new HashMap<>();
        for (FileNode child : dbChildren) {
            dbByName.put(child.getName(), child);
        }
        Map<String, RemoteFileEntry> remoteByName = new HashMap<>();
        for (RemoteFileEntry entry : remoteChildren) {
            remoteByName.put(entry.getName(), entry);
        }

        for (FileNode dbChild : dbChildren) {
            if (!remoteByName.containsKey(dbChild.getName())) {
                try {
                    deleteSubtree(dbChild);
                    context.incrementTotal();
                    context.incrementSuccess();
                } catch (Exception e) {
                    context.incrementFail();
                    context.addError("删除本地节点 " + dbChild.getName() + " 失败: " + e.getMessage());
                }
            }
        }

        for (RemoteFileEntry entry : remoteChildren) {
            FileNode dbChild = dbByName.get(entry.getName());
            try {
                processEntry(parentNode, dbChild, entry, adapter, context);
            } catch (Exception e) {
                context.incrementFail();
                context.addError("同步远程节点 " + entry.getName() + " 失败: " + e.getMessage());
            }
        }
    }

    private void processEntry(FileNode parentNode, FileNode dbChild, RemoteFileEntry entry,
                              RemoteProtocolAdapter adapter, RemoteSyncContext context) {
        context.incrementTotal();
        if (entry.isFolder()) {
            FileNode folderNode = dbChild;
            if (folderNode == null || !TYPE_FOLDER.equals(folderNode.getType())) {
                if (folderNode != null) {
                    deleteSubtree(folderNode);
                }
                folderNode = createNode(parentNode, entry, true, context);
                fileMapper.insert(folderNode);
            } else if (isMetaChanged(folderNode, entry)) {
                updateNodeMeta(folderNode, entry);
                fileMapper.updateById(folderNode);
            }
            context.incrementSuccess();
            syncFolder(folderNode, entry.getRemotePath(), adapter, context);
            return;
        }

        if (dbChild == null || !TYPE_FILE.equals(dbChild.getType())) {
            if (dbChild != null) {
                deleteSubtree(dbChild);
            }
            FileNode fileNode = createNode(parentNode, entry, false, context);
            fileMapper.insert(fileNode);
            context.incrementSuccess();
            return;
        }

        if (isMetaChanged(dbChild, entry)) {
            clearPreviewCacheIfChanged(dbChild, entry.getEtag());
            updateNodeMeta(dbChild, entry);
            fileMapper.updateById(dbChild);
        }
        context.incrementSuccess();
    }

    private FileNode createNode(FileNode parentNode, RemoteFileEntry entry, boolean folder, RemoteSyncContext context) {
        FileNode node = new FileNode();
        node.setId(IdUtil.nextId());
        node.setUserId(context.getUserId());
        node.setParentId(parentNode.getId());
        node.setName(entry.getName());
        node.setType(folder ? TYPE_FOLDER : TYPE_FILE);
        node.setSize(folder ? 0L : entry.getSize());
        node.setHash(entry.getEtag());
        node.setLastModified(entry.getLastModified());
        node.setSourceType(SOURCE_REMOTE);
        node.setRemoteMountId(context.getMountId());
        node.setPath(buildChildPath(parentNode));
        if (!folder) {
            node.setMimeType(URLConnection.guessContentTypeFromName(entry.getName()));
        }
        node.setStatus(1);
        return node;
    }

    private void updateNodeMeta(FileNode node, RemoteFileEntry entry) {
        node.setName(entry.getName());
        node.setSize(entry.isFolder() ? 0L : entry.getSize());
        node.setHash(entry.getEtag());
        node.setLastModified(entry.getLastModified());
        node.setUpdateTime(LocalDateTime.now());
        if (!entry.isFolder()) {
            node.setMimeType(URLConnection.guessContentTypeFromName(entry.getName()));
        }
    }

    private boolean isMetaChanged(FileNode node, RemoteFileEntry entry) {
        if (!entry.getName().equals(node.getName())) {
            return true;
        }
        Long size = entry.isFolder() ? 0L : entry.getSize();
        if (size == null) {
            size = 0L;
        }
        if (!size.equals(node.getSize())) {
            return true;
        }
        Long modified = entry.getLastModified();
        if (modified == null) {
            modified = 0L;
        }
        Long nodeModified = node.getLastModified();
        if (nodeModified == null) {
            nodeModified = 0L;
        }
        if (!modified.equals(nodeModified)) {
            return true;
        }
        String etag = entry.getEtag();
        if (etag != null && !etag.equals(node.getHash())) {
            return true;
        }
        return false;
    }

    private String buildChildPath(FileNode parentNode) {
        if (FileNodeConstants.ROOT_ID.equals(parentNode.getId())) {
            return FileNodeConstants.ROOT_ID;
        }
        return parentNode.getPath() + FileNodeConstants.PATH_SEPARATOR + parentNode.getId();
    }

    private void deleteSubtree(FileNode node) {
        List<FileNode> descendants = fileMapper.selectByIdPathPrefix(node.getUserId(), node.getPath(), node.getId());
        List<String> ids = new ArrayList<>(descendants.size());
        for (FileNode descendant : descendants) {
            ids.add(descendant.getId());
            clearPreviewCache(descendant.getId());
        }
        if (!ids.isEmpty()) {
            fileMapper.physicalDeleteByIds(ids);
        }
    }

    private void clearPreviewCacheIfChanged(FileNode node, String newEtag) {
        if (newEtag != null && !newEtag.equals(node.getHash())) {
            clearPreviewCache(node.getId());
        }
    }

    private void clearPreviewCache(String fileNodeId) {
        try {
            LambdaQueryWrapper<PreviewFile> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PreviewFile::getFileNodeId, fileNodeId);
            List<PreviewFile> previews = previewFileMapper.selectList(wrapper);
            for (PreviewFile preview : previews) {
                // 物理文件由系统数据目录管理，记录删除后由后台或用户再次预览时覆盖
                previewFileMapper.deleteById(preview.getId());
            }
        } catch (Exception e) {
            log.warn("清理远程文件预览缓存失败，fileNodeId={}", fileNodeId, e);
        }
    }

    private FileNode findMountNode(String remoteMountId, String userId) {
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileNode::getUserId, userId);
        wrapper.eq(FileNode::getRemoteMountId, remoteMountId);
        wrapper.eq(FileNode::getParentId, FileNodeConstants.ROOT_ID);
        wrapper.eq(FileNode::getSourceType, SOURCE_REMOTE);
        return fileMapper.selectOne(wrapper);
    }

    private WebDavConfig readConfig(String configJson) {
        try {
            WebDavConfig config = objectMapper.readValue(configJson, WebDavConfig.class);
            config.setPassword(remoteConfigCrypto.decrypt(config.getPassword()));
            return config;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "解析远程挂载配置失败");
        }
    }

    private void markRunning(RemoteSyncTask task) {
        RemoteSyncTask update = new RemoteSyncTask();
        update.setId(task.getId());
        update.setStatus(RemoteSyncTaskStatus.RUNNING.getValue());
        update.setStartTime(LocalDateTime.now());
        update.setUpdateTime(LocalDateTime.now());
        remoteSyncTaskMapper.updateById(update);
    }

    private void completeTask(RemoteSyncTask task, RemoteSyncContext context) {
        RemoteSyncTask update = new RemoteSyncTask();
        update.setId(task.getId());
        update.setStatus(context.getFailCount() > 0 ? RemoteSyncTaskStatus.PARTIAL.getValue()
                : RemoteSyncTaskStatus.COMPLETED.getValue());
        update.setEndTime(LocalDateTime.now());
        update.setTotalCount(context.getTotalCount());
        update.setSuccessCount(context.getSuccessCount());
        update.setFailCount(context.getFailCount());
        update.setErrorMsg(truncate(context.getErrorMessage()));
        update.setUpdateTime(LocalDateTime.now());
        remoteSyncTaskMapper.updateById(update);
    }

    private void failTask(RemoteSyncTask task, String errorMsg) {
        RemoteSyncTask update = new RemoteSyncTask();
        update.setId(task.getId());
        update.setStatus(RemoteSyncTaskStatus.FAILED.getValue());
        update.setEndTime(LocalDateTime.now());
        update.setErrorMsg(truncate(errorMsg));
        update.setUpdateTime(LocalDateTime.now());
        remoteSyncTaskMapper.updateById(update);
    }

    private void updateMountStatus(RemoteMount mount, RemoteSyncContext context) {
        RemoteMount update = new RemoteMount();
        update.setId(mount.getId());
        update.setLastSyncTime(LocalDateTime.now());
        update.setLastSyncStatus(context.getFailCount() > 0 ? RemoteSyncTaskStatus.PARTIAL.getValue()
                : RemoteSyncTaskStatus.COMPLETED.getValue());
        update.setLastSyncError(truncate(context.getErrorMessage()));
        update.setUpdateTime(LocalDateTime.now());
        remoteMountMapper.updateById(update);
    }

    private String truncate(String errorMsg) {
        if (errorMsg == null) {
            return null;
        }
        return errorMsg.length() > (int) MAX_ERROR_LENGTH ? errorMsg.substring(0, (int) MAX_ERROR_LENGTH) : errorMsg;
    }
}
