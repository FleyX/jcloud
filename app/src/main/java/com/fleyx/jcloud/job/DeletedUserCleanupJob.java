package com.fleyx.jcloud.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.StorageConstant;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.RecycleRecordMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
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
 * 删除用户级联清理任务。
 * <p>
 * 每天凌晨 3:30 执行，清理逻辑删除满 7 天（保留期）的用户数据：物理删除该用户的
 * 全部文件节点行与回收站记录，并删除其在所属存储空间下的物理目录（files / trash /
 * tmp 中对应 username 的目录）。用户行本身保留逻辑删除态作审计，Job 幂等，重跑无副作用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeletedUserCleanupJob {

    /**
     * 删除用户保留期天数（对齐回收站 7 天保留语义）。
     */
    private static final int RETENTION_DAYS = 7;

    private final UserMapper userMapper;
    private final FileMapper fileMapper;
    private final RecycleRecordMapper recycleRecordMapper;
    private final StorageSpaceMapper storageSpaceMapper;

    /**
     * 每天凌晨 3:30 执行清理。
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanup() {
        log.info("开始删除用户级联清理任务");
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS);
        List<User> expiredUsers = userMapper.selectExpiredDeletedUsers(cutoff);
        if (expiredUsers.isEmpty()) {
            log.info("无过期删除用户，清理结束");
            return;
        }

        int success = 0;
        int failed = 0;
        for (User user : expiredUsers) {
            try {
                cleanupUser(user);
                success++;
                log.info("删除用户级联清理完成: userId={}, username={}", user.getId(), user.getUsername());
            } catch (Exception e) {
                failed++;
                log.error("删除用户级联清理失败: userId={}, username={}", user.getId(), user.getUsername(), e);
            }
        }
        log.info("删除用户级联清理任务完成: 成功={}, 失败={}", success, failed);
    }

    /**
     * 清理单个逻辑删除用户：物理删除文件节点行与回收站记录，删除物理目录。
     */
    private void cleanupUser(User user) {
        fileMapper.delete(new LambdaQueryWrapper<FileNode>().eq(FileNode::getUserId, user.getId()));
        recycleRecordMapper.delete(new LambdaQueryWrapper<RecycleRecord>().eq(RecycleRecord::getUserId, user.getId()));
        cleanupPhysicalDirs(user);
    }

    /**
     * 删除用户所属存储空间下的 files / trash / tmp 物理目录（存在才删）。
     * <p>
     * 存储空间不存在或被逻辑删除时跳过物理目录并告警，交由管理员处理。
     */
    private void cleanupPhysicalDirs(User user) {
        if (user.getStorageSpaceId() == null) {
            log.warn("删除用户级联清理跳过物理目录: 用户未绑定存储空间, userId={}", user.getId());
            return;
        }
        StorageSpace space = storageSpaceMapper.selectById(user.getStorageSpaceId());
        if (space == null) {
            log.warn("删除用户级联清理跳过物理目录: 存储空间不存在或已删除, userId={}, storageSpaceId={}",
                    user.getId(), user.getStorageSpaceId());
            return;
        }
        deleteIfExists(Path.of(space.getPath(), StorageConstant.FILES_DIR, user.getUsername()));
        deleteIfExists(Path.of(space.getPath(), StorageConstant.TRASH_DIR, user.getUsername()));
        deleteIfExists(Path.of(space.getPath(), StorageConstant.TMP_DIR, user.getUsername()));
    }

    /**
     * 目录存在时递归删除，不存在直接跳过。
     */
    private void deleteIfExists(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try {
            deleteRecursively(path);
            log.info("已删除用户物理目录: {}", path);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "删除物理目录失败: " + path);
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
