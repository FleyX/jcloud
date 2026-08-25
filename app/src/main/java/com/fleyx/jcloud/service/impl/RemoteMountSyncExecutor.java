package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.SyncTaskStatus;
import com.fleyx.jcloud.common.event.RemoteMountSubmittedEvent;
import com.fleyx.jcloud.common.event.SyncCompletedEvent;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.PreviewFileMapper;
import com.fleyx.jcloud.mapper.RemoteMountMapper;
import com.fleyx.jcloud.mapper.RemoteSyncTaskMapper;
import com.fleyx.jcloud.model.bo.RemoteFileEntry;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.PreviewFile;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.po.RemoteSyncTask;
import com.fleyx.jcloud.service.RemoteProtocolAdapter;
import com.fleyx.jcloud.service.support.AbstractTreeSyncExecutor;
import com.fleyx.jcloud.service.support.RemoteMountSupport;
import com.fleyx.jcloud.service.support.SyncContext;
import com.fleyx.jcloud.service.support.SyncTaskSupport;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.IdUtil;
import com.fleyx.jcloud.util.RemoteMountLock;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URLConnection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 远程挂载同步任务执行器。
 * <p>
 * 目录对齐流程由 {@link AbstractTreeSyncExecutor} 承载，本类仅保留远程侧差异实现。
 */
@Slf4j
@Component
public class RemoteMountSyncExecutor extends AbstractTreeSyncExecutor<RemoteMountSyncExecutor.RemoteFolder, RemoteFileEntry> {

    private final RemoteSyncTaskMapper remoteSyncTaskMapper;
    private final RemoteMountMapper remoteMountMapper;
    private final PreviewFileMapper previewFileMapper;
    private final RemoteProtocolAdapterFactory adapterFactory;
    private final RemoteMountLock remoteMountLock;
    private final RemoteMountSupport remoteMountSupport;
    private final SyncTaskSupport syncTaskSupport;
    private final ApplicationEventPublisher eventPublisher;

    public RemoteMountSyncExecutor(FileMapper fileMapper,
                                   RemoteSyncTaskMapper remoteSyncTaskMapper,
                                   RemoteMountMapper remoteMountMapper,
                                   PreviewFileMapper previewFileMapper,
                                   RemoteProtocolAdapterFactory adapterFactory,
                                   RemoteMountLock remoteMountLock,
                                   RemoteMountSupport remoteMountSupport,
                                   SyncTaskSupport syncTaskSupport,
                                   ApplicationEventPublisher eventPublisher) {
        super(fileMapper);
        this.remoteSyncTaskMapper = remoteSyncTaskMapper;
        this.remoteMountMapper = remoteMountMapper;
        this.previewFileMapper = previewFileMapper;
        this.adapterFactory = adapterFactory;
        this.remoteMountLock = remoteMountLock;
        this.remoteMountSupport = remoteMountSupport;
        this.syncTaskSupport = syncTaskSupport;
        this.eventPublisher = eventPublisher;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(RemoteMountSubmittedEvent event) {
        execute(event.getTaskId());
    }

    /**
     * 异步重新投递指定任务（供调度器重发滞留 PENDING 任务使用，不阻塞调度线程）。
     *
     * @param taskId 任务 ID
     */
    @Async
    public void executeAsync(String taskId) {
        execute(taskId);
    }

    public void execute(String taskId) {
        RemoteSyncTask task = remoteSyncTaskMapper.selectById(taskId);
        if (task == null || !SyncTaskStatus.PENDING.getValue().equals(task.getStatus())) {
            return;
        }
        RemoteMount mount = remoteMountMapper.selectById(task.getRemoteMountId());
        if (mount == null || mount.getDeleteAt() != 0L) {
            syncTaskSupport.failTask(task, "挂载配置不存在或已删除", remoteSyncTaskMapper);
            return;
        }

        RLock lock = remoteMountLock.getLock(mount.getId());
        boolean locked = false;
        try {
            locked = lock.tryLock(SyncTaskSupport.LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                syncTaskSupport.failTask(task, "获取挂载点锁超时", remoteSyncTaskMapper);
                return;
            }
            doSync(task, mount);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            syncTaskSupport.failTask(task, "同步任务被中断", remoteSyncTaskMapper);
        } catch (Exception e) {
            log.error("远程挂载同步失败，taskId={}", taskId, e);
            syncTaskSupport.failTask(task, e.getMessage(), remoteSyncTaskMapper);
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    private void doSync(RemoteSyncTask task, RemoteMount mount) throws Exception {
        syncTaskSupport.markRunning(task, remoteSyncTaskMapper);
        RemoteProtocolAdapter adapter = adapterFactory.create(mount);
        FileNode mountNode = remoteMountSupport.findMountNode(mount.getId(), mount.getUserId());
        if (mountNode == null) {
            syncTaskSupport.failTask(task, "挂载点文件节点不存在", remoteSyncTaskMapper);
            return;
        }
        SyncContext context = new SyncContext(task.getId(), mount.getUserId(), mount.getId());

        syncFolder(mountNode, new RemoteFolder("/", adapter), context);
        syncTaskSupport.completeTask(task, context, remoteSyncTaskMapper);
        updateMountStatus(mount, context);
        // 同步完成（COMPLETED 或 PARTIAL）后发布事件，触发媒体库扫描等后续动作
        eventPublisher.publishEvent(new SyncCompletedEvent(this, mount.getUserId(),
                SyncCompletedEvent.TYPE_REMOTE_MOUNT));
    }

    /**
     * 远端目录标识（携带协议适配器）。
     *
     * @param remotePath 远端路径
     * @param adapter    协议适配器
     */
    public record RemoteFolder(String remotePath, RemoteProtocolAdapter adapter) {
    }

    @Override
    protected List<RemoteFileEntry> listChildren(RemoteFolder source, SyncContext context) {
        try {
            return source.adapter().listChildren(source.remotePath());
        } catch (Exception e) {
            context.addError("列出远程目录 " + source.remotePath() + " 失败: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected boolean isFolder(RemoteFileEntry entry) {
        return entry.isFolder();
    }

    @Override
    protected String nameOf(RemoteFileEntry entry) {
        return entry.getName();
    }

    @Override
    protected boolean metaChanged(FileNode dbNode, RemoteFileEntry entry) {
        return isMetaChanged(dbNode, entry);
    }

    @Override
    protected void applyMetaUpdate(FileNode dbNode, RemoteFileEntry entry) {
        if (!entry.isFolder()) {
            clearPreviewCacheIfChanged(dbNode, entry.getEtag());
        }
        updateNodeMeta(dbNode, entry);
        fileMapper.updateById(dbNode);
    }

    @Override
    protected FileNode createNode(FileNode parentNode, RemoteFileEntry entry, SyncContext context) {
        FileNode node = new FileNode();
        node.setId(IdUtil.nextId());
        node.setUserId(context.getUserId());
        node.setParentId(parentNode.getId());
        node.setName(entry.getName());
        node.setType(entry.isFolder() ? FileNodeConstants.TYPE_FOLDER : FileNodeConstants.TYPE_FILE);
        node.setSize(entry.isFolder() ? 0L : entry.getSize());
        node.setHash(entry.getEtag());
        node.setLastModified(entry.getLastModified());
        node.setSourceType(FileNodeConstants.SOURCE_REMOTE);
        node.setRemoteMountId(context.getTargetId());
        node.setPath(FilePathUtil.buildChildPath(parentNode));
        if (!entry.isFolder()) {
            node.setMimeType(URLConnection.guessContentTypeFromName(entry.getName()));
        }
        node.setStatus(1);
        return node;
    }

    @Override
    protected RemoteFolder childSource(RemoteFileEntry entry, RemoteFolder source) {
        return new RemoteFolder(entry.getRemotePath(), source.adapter());
    }

    @Override
    protected void deleteDbSubtree(FileNode node, SyncContext context) {
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

    @Override
    protected void recordDeleteFailure(FileNode dbChild, Exception e, SyncContext context) {
        context.recordFailure("删除本地节点 " + dbChild.getName() + " 失败: " + e.getMessage());
    }

    @Override
    protected void recordEntryFailure(RemoteFileEntry entry, Exception e, SyncContext context) {
        context.recordFailure("同步远程节点 " + entry.getName() + " 失败: " + e.getMessage());
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

    private void updateMountStatus(RemoteMount mount, SyncContext context) {
        RemoteMount update = new RemoteMount();
        update.setId(mount.getId());
        update.setLastSyncTime(LocalDateTime.now());
        update.setLastSyncStatus(context.getFailCount() > 0 ? SyncTaskStatus.PARTIAL.getValue()
                : SyncTaskStatus.COMPLETED.getValue());
        update.setLastSyncError(syncTaskSupport.truncate(context.getErrorMessage()));
        update.setUpdateTime(LocalDateTime.now());
        remoteMountMapper.updateById(update);
    }
}
