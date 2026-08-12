package com.fleyx.jcloud.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.StorageConstant;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.config.UploadProperties;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.model.po.StorageSpace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 分片上传临时目录清理任务。
 * <p>
 * 每小时执行一次，扫描各存储空间的 tmp 目录（storage/tmp/username/uploadId），
 * 删除超过保留时长（默认 24 小时，可配置）无活动的残留上传任务目录，释放被放弃
 * 的上传任务长期占用的磁盘。进行中的上传任务不受影响。
 * <p>
 * 只在 tmp 根目录下遍历两级，不触碰 files/trash/system 等目录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkedUploadTempCleanupJob {

    /**
     * 保留时长兜底值：配置非法（<= 0）时按 24 小时处理。
     */
    private static final long DEFAULT_RETENTION_HOURS = 24;

    private final StorageSpaceMapper storageSpaceMapper;
    private final UploadProperties uploadProperties;

    /**
     * 每小时整点执行清理。
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void cleanup() {
        log.info("开始分片上传临时目录清理任务");
        long retentionHours = uploadProperties.getTempRetentionHours();
        if (retentionHours <= 0) {
            retentionHours = DEFAULT_RETENTION_HOURS;
        }
        List<StorageSpace> spaces = storageSpaceMapper.selectList(new LambdaQueryWrapper<>());
        if (spaces.isEmpty()) {
            log.info("无存储空间，清理任务结束");
            return;
        }

        int deleted = 0;
        int failed = 0;
        for (StorageSpace space : spaces) {
            Path tmpRoot = Path.of(space.getPath(), StorageConstant.TMP_DIR);
            if (!Files.isDirectory(tmpRoot)) {
                continue;
            }
            try (Stream<Path> userDirs = Files.list(tmpRoot)) {
                for (Path userDir : userDirs.filter(Files::isDirectory).toList()) {
                    try (Stream<Path> uploadDirs = Files.list(userDir)) {
                        for (Path uploadDir : uploadDirs.filter(Files::isDirectory).toList()) {
                            try {
                                if (isExpired(uploadDir, retentionHours)) {
                                    deleteRecursively(uploadDir);
                                    deleted++;
                                    log.info("已删除超期分片上传临时目录: {}", uploadDir);
                                }
                            } catch (Exception e) {
                                failed++;
                                log.error("分片上传临时目录清理失败: {}", uploadDir, e);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                failed++;
                log.error("遍历分片上传临时目录失败: path={}", tmpRoot, e);
            }
        }
        log.info("分片上传临时目录清理任务完成: 删除目录数={}, 失败数={}", deleted, failed);
    }

    /**
     * 活动判定：目录内所有文件最后修改时间的最大值，超过保留时长即判定为超期；目录为空取目录自身修改时间。
     */
    private boolean isExpired(Path uploadDir, long retentionHours) throws IOException {
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(retentionHours);
        long maxMtime;
        try (Stream<Path> stream = Files.walk(uploadDir)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            if (files.isEmpty()) {
                maxMtime = Files.getLastModifiedTime(uploadDir).toMillis();
            } else {
                maxMtime = files.stream().mapToLong(this::lastModifiedMillis).max().orElse(Long.MAX_VALUE);
            }
        }
        return maxMtime < cutoff;
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            // 读取失败按当前时间处理，避免误删
            return System.currentTimeMillis();
        }
    }

    /**
     * 递归删除目录（参照 TrashPermanentDeleteSupport：Files.walk 逆序删除）。
     */
    private void deleteRecursively(Path path) throws IOException {
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new BusinessException(ResultCode.BUSINESS_ERROR, "删除物理文件失败: " + p);
                        }
                    });
        }
    }
}
