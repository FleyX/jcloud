package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.enums.MediaScanOutcome;
import com.fleyx.jcloud.common.enums.MediaScanStatus;
import com.fleyx.jcloud.common.enums.MediaType;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.service.MediaScanService;
import com.fleyx.jcloud.service.MediaScrapeService;
import com.fleyx.jcloud.service.support.MediaDirectorySourceSupport;
import com.fleyx.jcloud.service.support.MediaOtherScanSupport;
import com.fleyx.jcloud.service.support.MediaScanDriverSupport;
import com.fleyx.jcloud.service.support.MediaScanSupport;
import com.fleyx.jcloud.service.support.MediaMovieScanSupport;
import com.fleyx.jcloud.service.support.MediaTaskSupport;
import com.fleyx.jcloud.service.support.MediaTvScanSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 媒体库扫描服务实现。
 * <p>
 * 扫描只负责文件事实：三种媒体库均走新模型端到端扫描（电视 issue #17 / 电影 issue #18 /
 * 其他 issue #19），按来源目录子树遍历、文件变更哈希 diff、ffprobe 探测与按锚 reconcile，
 * 不访问 TMDB；变化条目的非手动匹配状态会被重置为未匹配，所属剧的匹配状态同步重置；
 * 扫描完成后自动提交一次非强制削刮（削刮仍是独立任务，扫描本身不访问 TMDB）。
 * 旧模型 scanSource/scanFile 扫描路径已随 issue #21 弃表删除。
 * <p>
 * 并发模型：扫描与削刮按媒体库互斥（{@link MediaTaskSupport}），协作式取消，
 * 同一媒体库的多次扫描请求通过 {@link #pendingScans} 合并，由单线程循环拾取。
 */
@Slf4j
@Service
public class MediaScanServiceImpl implements MediaScanService {

    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final UserMapper userMapper;
    private final MediaScanSupport mediaScanSupport;
    private final MediaTaskSupport mediaTaskSupport;
    private final MediaScrapeService mediaScrapeService;
    private final MediaDirectorySourceSupport sourceSupport;
    private final MediaScanDriverSupport mediaScanDriverSupport;
    private final MediaTvScanSupport mediaTvScanSupport;
    private final MediaMovieScanSupport mediaMovieScanSupport;
    private final MediaOtherScanSupport mediaOtherScanSupport;
    private final TaskExecutor taskExecutor;

    public MediaScanServiceImpl(MediaDirectoryMapper mediaDirectoryMapper,
                                UserMapper userMapper,
                                MediaScanSupport mediaScanSupport,
                                MediaTaskSupport mediaTaskSupport, MediaScrapeService mediaScrapeService,
                                MediaDirectorySourceSupport sourceSupport,
                                MediaScanDriverSupport mediaScanDriverSupport,
                                MediaTvScanSupport mediaTvScanSupport,
                                MediaMovieScanSupport mediaMovieScanSupport,
                                MediaOtherScanSupport mediaOtherScanSupport,
                                @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.mediaDirectoryMapper = mediaDirectoryMapper;
        this.userMapper = userMapper;
        this.mediaScanSupport = mediaScanSupport;
        this.mediaTaskSupport = mediaTaskSupport;
        this.mediaScrapeService = mediaScrapeService;
        this.sourceSupport = sourceSupport;
        this.mediaScanDriverSupport = mediaScanDriverSupport;
        this.mediaTvScanSupport = mediaTvScanSupport;
        this.mediaMovieScanSupport = mediaMovieScanSupport;
        this.mediaOtherScanSupport = mediaOtherScanSupport;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 待执行的扫描请求（directoryId → 是否强制全量），同库多次触发会合并。
     */
    private final Map<String, Boolean> pendingScans = new ConcurrentHashMap<>();

    @Override
    public void submitScan(String directoryId, String userId) {
        submitScan(directoryId, userId, false);
    }

    @Override
    public void submitScan(String directoryId, String userId, boolean force) {
        pendingScans.merge(directoryId, force, (a, b) -> a || b);
        taskExecutor.execute(() -> runLoop(directoryId));
    }

    @Override
    public void scan(String directoryId) {
        pendingScans.merge(directoryId, false, (a, b) -> a || b);
        runLoop(directoryId);
    }

    @Override
    public void requestCancel(String directoryId) {
        mediaTaskSupport.requestCancel(directoryId);
    }

    /**
     * 扫描主循环：同一媒体库只跑一个线程，循环拾取待扫描请求直至清空。
     * 媒体库被削刮任务占用时延迟重试。扫描成功完成后自动提交一次非强制削刮。
     */
    private void runLoop(String directoryId) {
        if (!mediaTaskSupport.enter(directoryId, MediaTaskSupport.TASK_SCAN)) {
            if (pendingScans.containsKey(directoryId)) {
                log.info("媒体库任务被占用，延迟重试扫描: {}", directoryId);
                taskExecutor.execute(() -> {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    runLoop(directoryId);
                });
            }
            return;
        }
        boolean completed = false;
        try {
            Boolean force;
            while ((force = pendingScans.remove(directoryId)) != null) {
                mediaTaskSupport.clearCancel(directoryId);
                completed |= scanOnce(directoryId, force);
            }
        } finally {
            mediaTaskSupport.exit(directoryId, MediaTaskSupport.TASK_SCAN);
        }
        if (completed) {
            submitScrapeAfterScan(directoryId);
        }
    }

    /**
     * 扫描完成后自动提交非强制削刮（只处理未匹配内容）。提交失败仅记日志。
     */
    private void submitScrapeAfterScan(String directoryId) {
        MediaDirectory directory = mediaDirectoryMapper.selectById(directoryId);
        if (directory == null) {
            return;
        }
        try {
            mediaScrapeService.submitScrape(directoryId, directory.getUserId(), false);
        } catch (Exception e) {
            log.info("扫描后自动削刮提交失败: {}, {}", directoryId, e.getMessage());
        }
    }

    /**
     * @return 扫描是否成功完成（完成或部分完成；中断、异常视为未完成）
     */
    private boolean scanOnce(String directoryId, boolean force) {
        MediaDirectory directory = mediaDirectoryMapper.selectById(directoryId);
        if (directory == null) {
            return false;
        }
        markScanning(directory);
        try {
            MediaScanOutcome outcome = doScan(directory, force);
            switch (outcome) {
                case COMPLETED -> updateScanResult(directory, MediaScanStatus.COMPLETED.name(), null);
                case PARTIAL -> updateScanResult(directory, MediaScanStatus.PARTIAL.name(), "部分文件扫描失败");
                case CANCELLED -> updateScanResult(directory, MediaScanStatus.FAILED.name(), "扫描已中断");
            }
            return outcome != MediaScanOutcome.CANCELLED;
        } catch (Exception e) {
            log.error("媒体库扫描失败: {}", directoryId, e);
            updateScanResult(directory, MediaScanStatus.FAILED.name(), e.getMessage());
            return false;
        }
    }

    private MediaScanOutcome doScan(MediaDirectory directory, boolean force) {
        String userId = directory.getUserId();
        String username = userMapper.selectById(userId).getUsername();
        List<MediaDirectorySource> sources = sourceSupport.listByDirectoryId(directory.getId());

        MediaType mediaType = MediaType.of(directory.getMediaType());
        return switch (mediaType) {
            // 分派点保留在 Service：按类型取策略调共享驱动器（票据 07 共享骨架）
            case TV -> mediaScanDriverSupport.scanDirectory(directory, sources, force, username, mediaTvScanSupport);
            case MOVIE -> mediaScanDriverSupport.scanDirectory(directory, sources, force, username,
                    mediaMovieScanSupport);
            case OTHER -> mediaScanDriverSupport.scanDirectory(directory, sources, force, username,
                    mediaOtherScanSupport);
        };
    }

    /**
     * 扫描开始时标记媒体库为扫描中。
     */
    private void markScanning(MediaDirectory directory) {
        MediaDirectory update = new MediaDirectory();
        update.setId(directory.getId());
        update.setLastScanStatus(MediaScanStatus.SCANNING.name());
        update.setLastScanError(null);
        mediaDirectoryMapper.updateById(update);
    }

    private void updateScanResult(MediaDirectory directory, String status, String error) {
        MediaDirectory update = new MediaDirectory();
        update.setId(directory.getId());
        update.setLastScanTime(LocalDateTime.now());
        update.setLastScanStatus(status);
        update.setLastScanError(error);
        if (directory.getScanCron() != null && !directory.getScanCron().isBlank()
                && CronExpression.isValidExpression(directory.getScanCron())) {
            update.setNextScanTime(CronExpression.parse(directory.getScanCron()).next(LocalDateTime.now()));
        }
        mediaDirectoryMapper.updateById(update);
    }
}
