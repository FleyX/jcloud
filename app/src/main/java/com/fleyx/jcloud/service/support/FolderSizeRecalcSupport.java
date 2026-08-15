package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.event.FileTreeChangedEvent;
import com.fleyx.jcloud.common.event.SyncCompletedEvent;
import com.fleyx.jcloud.config.FileProperties;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.User;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 文件夹大小持久化统计支持组件（票据 03）。
 * <p>
 * 订阅文件树变更事件：归集受影响祖先链（含移动/恢复的新旧双侧）入内存队列防抖
 * （距最后一条匹配成功的事件无新事件才处理，窗口可配置）后按 path 深度从深到浅逐层
 * 重算 {@code folder.size = Σ 直接子节点 size}；远程文件节点天然在内，回收站内容已物理
 * 移出 t_file_node 天然不计。同步完成事件不走防抖，直接对受影响子树重算（TYPE_USER 全树，
 * TYPE_REMOTE_MOUNT 各挂载点子树）。全量对账供启动/每日定时兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FolderSizeRecalcSupport {

    private final FileMapper fileMapper;
    private final UserMapper userMapper;
    private final FileProperties fileProperties;

    /**
     * 防抖窗口（毫秒），由 {@link FileProperties#getFolderSizeDebounceSeconds()} 初始化。
     * volatile：单测通过反射缩短窗口，保证调度线程立即可见。
     */
    private volatile long debounceWindowMs;

    /**
     * 防抖队列与调度状态（由 {@link #lock} 保护）：文件夹 ID → 用户 ID。
     */
    private final Object lock = new Object();
    private final Map<String, String> pendingFolders = new HashMap<>();
    private long lastMatchedAt;
    private boolean scheduled;

    /**
     * 防抖调度线程（单线程）。
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "folder-size-debounce");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    void initDebounceWindow() {
        debounceWindowMs = TimeUnit.SECONDS.toMillis(fileProperties.getFolderSizeDebounceSeconds());
    }

    /**
     * 关闭防抖调度线程。
     */
    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }

    /**
     * 监听文件树变更事件：归集受影响文件夹 ID（新旧父链祖先 + 变更节点自身若是文件夹）入防抖队列。
     * <p>
     * 事件在事务提交后发布（afterCommit 回调，已脱离事务同步），此处直接监听、不用事务监听；
     * 异步执行，不阻塞写操作。PERMANENT_DELETE 双侧路径为空，无祖先可归集，自然跳过。
     */
    @Async
    @EventListener
    public void onFileTreeChanged(FileTreeChangedEvent event) {
        Set<String> folderIds = new HashSet<>();
        collectAncestors(event.getOldPath(), folderIds);
        collectAncestors(event.getNewPath(), folderIds);
        if (FileNodeConstants.TYPE_FOLDER.equals(event.getNodeType())) {
            folderIds.add(event.getNodeId());
        }
        if (folderIds.isEmpty()) {
            return;
        }
        synchronized (lock) {
            for (String folderId : folderIds) {
                pendingFolders.put(folderId, event.getUserId());
            }
            lastMatchedAt = System.currentTimeMillis();
            if (!scheduled) {
                scheduled = true;
                scheduler.schedule(this::debounceFire, debounceWindowMs, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * 收集物化路径（父链，'.' 分隔，首段恒为虚拟根）中的非根祖先 ID。
     */
    private void collectAncestors(String path, Set<String> folderIds) {
        if (path == null || path.isBlank()) {
            return;
        }
        for (String id : path.split("\\.")) {
            if (!FileNodeConstants.ROOT_ID.equals(id)) {
                folderIds.add(id);
            }
        }
    }

    /**
     * 防抖到期处理：距最后一条匹配事件不足窗口则按剩余时间重排，否则排空队列，
     * 按 path 深度从深到浅逐层重算各文件夹大小（物理已删的文件夹自动跳过）。
     */
    private void debounceFire() {
        long remaining = remainingDebounceMs();
        if (remaining > 0) {
            scheduler.schedule(this::debounceFire, remaining, TimeUnit.MILLISECONDS);
            return;
        }
        Map<String, String> batch = drainPending();
        if (batch.isEmpty()) {
            return;
        }
        List<FileNode> folders = fileMapper.selectBatchIds(batch.keySet()).stream()
                .sorted(Comparator.comparingInt(this::pathDepth).reversed())
                .toList();
        for (FileNode folder : folders) {
            if (!FileNodeConstants.TYPE_FOLDER.equals(folder.getType())) {
                continue;
            }
            recomputeFolder(batch.get(folder.getId()), folder);
        }
    }

    /**
     * 距最后一条匹配事件的剩余防抖时间（毫秒）。
     */
    private long remainingDebounceMs() {
        synchronized (lock) {
            return Math.max(0, debounceWindowMs - (System.currentTimeMillis() - lastMatchedAt));
        }
    }

    /**
     * 排空防抖队列并清除在途标记（与入队同锁互斥，避免排空后新事件丢失）。
     */
    private Map<String, String> drainPending() {
        synchronized (lock) {
            Map<String, String> batch = new HashMap<>(pendingFolders);
            pendingFolders.clear();
            scheduled = false;
            return batch;
        }
    }

    /**
     * 单文件夹重算：size = Σ 直接子节点 size（子节点含子文件夹持久化汇总值与文件大小，
     * 远程文件节点天然在内；回收站内容已物理移出 t_file_node 天然不计），值变化才更新。
     */
    private void recomputeFolder(String userId, FileNode folder) {
        long size = fileMapper.selectByParentId(userId, folder.getId()).stream()
                .mapToLong(this::sizeOf)
                .sum();
        if (size != folder.getSize()) {
            updateSize(folder.getId(), size);
        }
    }

    /**
     * 监听同步完成事件，不走防抖直接重算受影响子树：
     * TYPE_USER 全量重算该用户整个文件树，TYPE_REMOTE_MOUNT 对该用户全部挂载点子树重算。
     * 异步执行，不阻塞同步任务本身。
     */
    @Async
    @EventListener
    public void onSyncCompleted(SyncCompletedEvent event) {
        if (SyncCompletedEvent.TYPE_USER.equals(event.getSyncType())) {
            reconcileUser(event.getUserId());
        } else if (SyncCompletedEvent.TYPE_REMOTE_MOUNT.equals(event.getSyncType())) {
            recomputeRemoteMounts(event.getUserId());
        }
    }

    /**
     * 该用户全部远程挂载点子树重算；单挂载点失败仅记日志，不影响其余挂载点。
     */
    private void recomputeRemoteMounts(String userId) {
        List<FileNode> mounts = fileMapper.selectList(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getUserId, userId)
                .eq(FileNode::getParentId, FileNodeConstants.ROOT_ID)
                .eq(FileNode::getSourceType, FileNodeConstants.SOURCE_REMOTE));
        for (FileNode mount : mounts) {
            try {
                recomputeSubtree(userId, mount);
            } catch (Exception e) {
                log.warn("远程挂载子树重算失败: userId={}, mountId={}, error={}",
                        userId, mount.getId(), e.getMessage());
            }
        }
    }

    private void recomputeSubtree(String userId, FileNode node) {
        List<FileNode> nodes = fileMapper.selectByIdPathPrefix(userId, node.getPath(), node.getId());
        recomputeTree(nodes);
    }

    /**
     * 全量对账（启动/每日定时兜底）：逐用户全树重算，单用户失败仅记日志，不影响其余用户。
     */
    @Async
    public void reconcileAll() {
        List<User> users = userMapper.selectList(null);
        int success = 0;
        for (User user : users) {
            try {
                reconcileUser(user.getId());
                success++;
            } catch (Exception e) {
                log.warn("文件夹大小对账失败: userId={}, error={}", user.getId(), e.getMessage());
            }
        }
        log.info("文件夹大小全量对账完成: 用户数={}, 成功={}", users.size(), success);
    }

    /**
     * 单用户全树重算。
     *
     * @param userId 用户 ID
     */
    public void reconcileUser(String userId) {
        List<FileNode> nodes = fileMapper.selectList(
                new LambdaQueryWrapper<FileNode>().eq(FileNode::getUserId, userId));
        if (nodes.isEmpty()) {
            return;
        }
        recomputeTree(nodes);
    }

    /**
     * 子树/全树重算算法（供同步事件与兜底共用）：一次查出范围内全部节点，内存按 parentId
     * 分组，按 path 深度从深到浅逐层算 {@code folder.size = Σ 直接子节点 size}，仅更新变化值。
     */
    private void recomputeTree(List<FileNode> nodes) {
        Map<String, List<FileNode>> childrenByParent = new HashMap<>();
        for (FileNode node : nodes) {
            if (node.getParentId() != null) {
                childrenByParent.computeIfAbsent(node.getParentId(), k -> new ArrayList<>()).add(node);
            }
        }
        List<FileNode> folders = nodes.stream()
                .filter(n -> FileNodeConstants.TYPE_FOLDER.equals(n.getType()))
                .sorted(Comparator.comparingInt(this::pathDepth).reversed())
                .toList();
        for (FileNode folder : folders) {
            long size = childrenByParent.getOrDefault(folder.getId(), List.of()).stream()
                    .mapToLong(this::sizeOf)
                    .sum();
            if (size != folder.getSize()) {
                folder.setSize(size);
                updateSize(folder.getId(), size);
            }
        }
    }

    /**
     * 仅更新节点 size 字段。
     */
    private void updateSize(String id, long size) {
        FileNode update = new FileNode();
        update.setId(id);
        update.setSize(size);
        fileMapper.updateById(update);
    }

    private long sizeOf(FileNode node) {
        return node.getSize() == null ? 0L : node.getSize();
    }

    /**
     * 节点深度：path 中除虚拟根外的段数（深者先重算）。
     */
    private int pathDepth(FileNode node) {
        String path = node.getPath();
        if (path == null || path.isBlank()) {
            return 0;
        }
        int depth = 0;
        for (String segment : path.split("\\.")) {
            if (!FileNodeConstants.ROOT_ID.equals(segment)) {
                depth++;
            }
        }
        return depth;
    }
}
