package com.fleyx.jcloud.job;

import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.mapper.PreviewFileMapper;
import com.fleyx.jcloud.model.po.PreviewFile;
import com.fleyx.jcloud.service.support.MediaSubtitleConvertSupport;
import com.fleyx.jcloud.service.support.SystemCacheDirProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 系统缓存清理任务。
 * <p>
 * 每天凌晨 4 点执行（错开回收站 2:00、对账 3:00、删用户 3:30），统一清理系统缓存目录下三类残留：
 * 超过 90 天的预览（物理文件与 {@code t_preview_file} 行一致删除）、超过 30 天的字幕缓存文件、
 * 超过 1 天的转码残留会话目录。应用启动时另行清空转码根目录，避免服务重启遗留的孤儿切片。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemCacheCleanupJob {

    /**
     * 预览缓存保留天数。
     */
    private static final int PREVIEW_TTL_DAYS = 90;

    /**
     * 字幕缓存保留天数。
     */
    private static final int SUBTITLE_TTL_DAYS = 30;

    /**
     * 转码残留会话目录保留天数。
     */
    private static final int TRANSCODE_RESIDUE_TTL_DAYS = 1;

    /**
     * 转码会话根目录（系统缓存目录下的一级子目录）。
     */
    private static final String TRANSCODE_DIR = "media/transcode";

    private final SystemCacheDirProvider systemCacheDirProvider;
    private final PreviewFileMapper previewFileMapper;

    /**
     * 每天凌晨 4 点执行三类清理，各自兜底异常互不影响。
     */
    @Scheduled(cron = "0 0 4 * * ?")
    public void cleanup() {
        log.info("开始系统缓存清理任务");
        int previewDeleted = 0;
        int subtitleDeleted = 0;
        int transcodeDeleted = 0;
        try {
            previewDeleted = cleanupExpiredPreviews();
        } catch (Exception e) {
            log.error("预览缓存清理失败", e);
        }
        try {
            subtitleDeleted = cleanupExpiredSubtitles();
        } catch (Exception e) {
            log.error("字幕缓存清理失败", e);
        }
        try {
            transcodeDeleted = cleanupResidueTranscodeSessions();
        } catch (Exception e) {
            log.error("转码残留会话清理失败", e);
        }
        log.info("系统缓存清理任务完成: 预览删除={}, 字幕删除={}, 转码会话删除={}",
                previewDeleted, subtitleDeleted, transcodeDeleted);
    }

    /**
     * 应用启动时清空转码根目录的内容（保留根目录本身），清理服务重启遗留的孤儿切片。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void clearTranscodeDirOnStartup() {
        Path transcodeDir = transcodeRoot();
        if (!Files.isDirectory(transcodeDir)) {
            log.info("转码根目录不存在，启动清空跳过: {}", transcodeDir);
            return;
        }
        int cleared = 0;
        try (Stream<Path> entries = Files.list(transcodeDir)) {
            for (Path entry : entries.toList()) {
                if (FileUtil.del(entry.toFile())) {
                    cleared++;
                } else {
                    log.warn("启动清空转码根目录条目失败: {}", entry);
                }
            }
        } catch (IOException e) {
            log.error("启动清空转码根目录失败: path={}", transcodeDir, e);
            return;
        }
        log.info("启动清空转码根目录完成: path={}, 清理条目数={}", transcodeDir, cleared);
    }

    /**
     * 清理超过 {@link #PREVIEW_TTL_DAYS} 天的预览：逐行删除物理文件后批量物理删除 DB 行。
     *
     * @return 删除的预览行数
     */
    private int cleanupExpiredPreviews() {
        LocalDateTime expireTime = LocalDateTime.now().minusDays(PREVIEW_TTL_DAYS);
        List<PreviewFile> expired = previewFileMapper.selectList(
                new LambdaQueryWrapper<PreviewFile>().lt(PreviewFile::getCreateTime, expireTime));
        if (expired.isEmpty()) {
            log.info("无过期预览缓存");
            return 0;
        }

        List<String> deletedIds = new ArrayList<>(expired.size());
        for (PreviewFile preview : expired) {
            deletePreviewFile(preview.getRelativePath());
            deletedIds.add(preview.getId());
        }
        previewFileMapper.physicalDeleteByIds(deletedIds);
        log.info("预览缓存清理完成: 删除行数={}", deletedIds.size());
        return deletedIds.size();
    }

    /**
     * 删除预览物理文件，文件不存在不视为失败。
     */
    private void deletePreviewFile(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        Path file = systemCacheDirProvider.getCacheDir().resolve(relativePath);
        try {
            if (Files.deleteIfExists(file)) {
                log.info("已删除过期预览文件: {}", file);
            }
        } catch (IOException e) {
            log.warn("过期预览文件删除失败: {}", file, e);
        }
    }

    /**
     * 清理超过 {@link #SUBTITLE_TTL_DAYS} 天的字幕缓存文件（按文件修改时间扫描）。
     *
     * @return 删除的字幕文件数
     */
    private int cleanupExpiredSubtitles() {
        Path subtitleDir = systemCacheDirProvider.getCacheDir()
                .resolve(MediaSubtitleConvertSupport.SUBTITLE_CACHE_DIR);
        if (!Files.isDirectory(subtitleDir)) {
            log.info("字幕缓存目录不存在，跳过: {}", subtitleDir);
            return 0;
        }

        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(SUBTITLE_TTL_DAYS);
        int deleted = 0;
        try (Stream<Path> entries = Files.list(subtitleDir)) {
            for (Path file : entries.filter(Files::isRegularFile).toList()) {
                try {
                    if (lastModifiedMillis(file) < cutoff) {
                        Files.deleteIfExists(file);
                        deleted++;
                        log.info("已删除过期字幕缓存文件: {}", file);
                    }
                } catch (IOException e) {
                    log.warn("过期字幕缓存文件删除失败: {}", file, e);
                }
            }
        } catch (IOException e) {
            log.error("遍历字幕缓存目录失败: path={}", subtitleDir, e);
        }
        log.info("字幕缓存清理完成: 删除文件数={}", deleted);
        return deleted;
    }

    /**
     * 清理超过 {@link #TRANSCODE_RESIDUE_TTL_DAYS} 天的转码残留会话目录（按目录修改时间扫描，递归删除）。
     *
     * @return 删除的会话目录数
     */
    private int cleanupResidueTranscodeSessions() {
        Path transcodeDir = transcodeRoot();
        if (!Files.isDirectory(transcodeDir)) {
            log.info("转码根目录不存在，跳过: {}", transcodeDir);
            return 0;
        }

        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(TRANSCODE_RESIDUE_TTL_DAYS);
        int deleted = 0;
        try (Stream<Path> entries = Files.list(transcodeDir)) {
            for (Path sessionDir : entries.filter(Files::isDirectory).toList()) {
                if (lastModifiedMillis(sessionDir) < cutoff && FileUtil.del(sessionDir.toFile())) {
                    deleted++;
                    log.info("已删除转码残留会话目录: {}", sessionDir);
                }
            }
        } catch (IOException e) {
            log.error("遍历转码根目录失败: path={}", transcodeDir, e);
        }
        log.info("转码残留会话清理完成: 删除目录数={}", deleted);
        return deleted;
    }

    private Path transcodeRoot() {
        return systemCacheDirProvider.getCacheDir().resolve(TRANSCODE_DIR);
    }

    /**
     * 读取最后修改时间；读取失败按当前时间处理，避免误删。
     */
    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return System.currentTimeMillis();
        }
    }
}
