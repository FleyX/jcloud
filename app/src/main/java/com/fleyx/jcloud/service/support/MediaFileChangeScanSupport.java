package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.event.FileTreeChangedEvent;
import com.fleyx.jcloud.config.MediaProperties;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.service.MediaScanService;
import com.fleyx.jcloud.util.FilePathUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 影视库文件变更自动刷新组件（ADR 0025「文件变更广播」）。
 * <p>
 * 订阅文件树变更事件：变更节点新旧父链命中任一媒体库来源目录子树（物化路径前缀）则属于该库，
 * 进入内存队列防抖（距最后一条匹配成功的事件无新事件才处理，窗口可配置）后统一提交库级增量扫描；
 * 扫描完成后的级联非强制削刮为扫描链路内建行为，此处不重复触发。movie/tv/other 三型库均纳入。
 * 库外事件直接丢弃；单库提交失败仅记日志，不影响其余库。
 */
@Slf4j
@Component
public class MediaFileChangeScanSupport {

    /**
     * 防抖窗口（毫秒）。volatile：单测通过反射缩短窗口，保证调度线程立即可见。
     */
    private volatile long debounceWindowMs;

    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final MediaDirectorySourceSupport sourceSupport;
    private final FileMapper fileMapper;
    private final MediaScanService mediaScanService;

    /**
     * 来源节点最近已知完整 id 路径缓存（fileNodeId → 完整 id 路径）。
     * 兜底来源目录已被物理删除（回收站物理删）时事件到达无法回查节点的场景。
     */
    private final Map<String, String> sourceFullPathCache = new ConcurrentHashMap<>();

    /**
     * 防抖队列与调度状态（由 {@link #lock} 保护）。
     */
    private final Object lock = new Object();
    private final Map<String, String> pendingDirectories = new HashMap<>();
    private long lastMatchedAt;
    private boolean scheduled;

    /**
     * 防抖调度线程（单线程）。
     */
    private final ScheduledExecutorService scheduler;

    public MediaFileChangeScanSupport(MediaDirectoryMapper mediaDirectoryMapper,
                                      MediaDirectorySourceSupport sourceSupport,
                                      FileMapper fileMapper,
                                      MediaScanService mediaScanService,
                                      MediaProperties mediaProperties) {
        this.mediaDirectoryMapper = mediaDirectoryMapper;
        this.sourceSupport = sourceSupport;
        this.fileMapper = fileMapper;
        this.mediaScanService = mediaScanService;
        this.debounceWindowMs = TimeUnit.SECONDS.toMillis(mediaProperties.getFileChangeDebounceSeconds());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "media-file-change-debounce");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 关闭防抖调度线程。
     */
    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }

    /**
     * 监听文件树变更事件：命中媒体库的进入防抖队列，库外事件直接丢弃。
     * <p>
     * 事件在事务提交后发布（afterCommit 回调，已脱离事务同步），此处直接监听、不用事务监听；
     * 异步执行，不阻塞写操作。
     */
    @Async
    @EventListener
    public void onFileTreeChanged(FileTreeChangedEvent event) {
        List<MediaDirectory> directories = mediaDirectoryMapper.selectList(
                new LambdaQueryWrapper<MediaDirectory>().eq(MediaDirectory::getUserId, event.getUserId()));
        if (directories.isEmpty()) {
            return;
        }
        Map<String, List<MediaDirectorySource>> sourcesByDirectory =
                sourceSupport.mapByDirectoryIds(directories.stream().map(MediaDirectory::getId).toList());
        boolean matched = false;
        for (MediaDirectory directory : directories) {
            for (MediaDirectorySource source : sourcesByDirectory.getOrDefault(directory.getId(), List.of())) {
                if (isAffected(source, event)) {
                    enqueue(directory.getId(), event.getUserId());
                    matched = true;
                    break;
                }
            }
        }
        if (matched) {
            log.debug("文件变更命中媒体库，进入防抖队列: userId={}, nodeId={}, operation={}",
                    event.getUserId(), event.getNodeId(), event.getOperation());
        }
    }

    /**
     * 判断来源目录是否受本次变更影响。
     * <p>
     * 来源目录完整路径取库内节点（先查库并刷新缓存，物理删除后查不到则用最近已知缓存）；
     * 均不可得时仅按变更节点 ID 与来源目录节点 ID 相等兜底（首轮即整库删除且缓存为空的
     * 极端场景放弃匹配，由 cron 定时扫描兜底，PRD 已声明此取舍）。
     */
    private boolean isAffected(MediaDirectorySource source, FileTreeChangedEvent event) {
        String sourceFull = resolveSourceFullPath(source.getFileNodeId());
        if (sourceFull != null) {
            return matchesChangedNode(sourceFull, event);
        }
        return source.getFileNodeId().equals(event.getNodeId());
    }

    /**
     * 解析来源目录节点完整 id 路径：先查库并刷新缓存，查不到用最近已知缓存，都没有返回 null。
     */
    private String resolveSourceFullPath(String fileNodeId) {
        FileNode node = fileMapper.selectById(fileNodeId);
        if (node != null) {
            String fullPath = FilePathUtil.fullIdPath(node);
            sourceFullPathCache.put(fileNodeId, fullPath);
            return fullPath;
        }
        return sourceFullPathCache.get(fileNodeId);
    }

    /**
     * 变更节点任一非空父链（旧/新侧）完整路径与来源目录命中：来源目录是变更节点祖先（变更发生在库内），
     * 或变更节点是来源目录祖先/本身（来源目录被移动/重命名/删除）。
     */
    private boolean matchesChangedNode(String sourceFull, FileTreeChangedEvent event) {
        for (String sidePath : new String[]{event.getOldPath(), event.getNewPath()}) {
            if (sidePath == null || sidePath.isBlank()) {
                continue;
            }
            String nodeFull = sidePath + FileNodeConstants.PATH_SEPARATOR + event.getNodeId();
            if (isAncestorOrSame(sourceFull, nodeFull) || isAncestorOrSame(nodeFull, sourceFull)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断 ancestor 是否为 descendant 的祖先（含相等），带路径分隔符边界。
     */
    private boolean isAncestorOrSame(String ancestor, String descendant) {
        return ancestor.equals(descendant)
                || descendant.startsWith(ancestor + FileNodeConstants.PATH_SEPARATOR);
    }

    /**
     * 匹配成功：入库并刷新最近匹配时间；无在途防抖任务则调度窗口后触发。
     */
    private void enqueue(String directoryId, String userId) {
        synchronized (lock) {
            pendingDirectories.put(directoryId, userId);
            lastMatchedAt = System.currentTimeMillis();
            if (!scheduled) {
                scheduled = true;
                scheduler.schedule(this::debounceFire, debounceWindowMs, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * 防抖到期处理：距最后一条匹配事件不足窗口则按剩余时间重排，否则排空队列逐库提交扫描。
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
        log.info("影视库文件变更防抖到期，提交扫描: 媒体库数={}", batch.size());
        for (Map.Entry<String, String> entry : batch.entrySet()) {
            try {
                mediaScanService.submitScan(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.warn("媒体库扫描提交失败: directory={}, error={}", entry.getKey(), e.getMessage());
            }
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
            Map<String, String> batch = new HashMap<>(pendingDirectories);
            pendingDirectories.clear();
            scheduled = false;
            return batch;
        }
    }
}
