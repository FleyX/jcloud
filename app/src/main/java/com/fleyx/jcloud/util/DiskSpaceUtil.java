package com.fleyx.jcloud.util;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.po.StorageSpace;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 磁盘空间工具类。
 * <p>
 * 根据存储空间物理路径，使用 {@link FileStore} 自动探测总容量、已用空间和剩余空间，
 * 语义等价于 {@code df -h}，但跨平台且可解析。
 */
@Slf4j
public final class DiskSpaceUtil {

    private DiskSpaceUtil() {
        // 工具类禁止实例化
    }

    /**
     * 刷新存储空间的容量、已用空间和剩余空间。
     *
     * @param space 存储空间实体，要求 path 字段非空
     */
    public static void refreshSpace(StorageSpace space) {
        if (space == null || space.getPath() == null) {
            return;
        }
        Path path = Path.of(space.getPath());
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
            if (!Files.isDirectory(path)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "物理路径必须是目录: " + space.getPath());
            }
            FileStore store = Files.getFileStore(path);
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            long used = total - free;
            space.setCapacity(total);
            space.setUsedSpace(Math.max(used, 0L));
            space.setFreeSpace(free);
        } catch (IOException e) {
            log.error("获取磁盘空间信息失败: {}", space.getPath(), e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "获取磁盘空间信息失败: " + space.getPath(), e);
        }
    }

    /**
     * 仅计算并返回指定路径的磁盘空间信息，不修改实体。
     *
     * @param path 物理路径
     * @return 数组 [total, used, free]
     */
    public static long[] calculateSpace(String path) {
        StorageSpace space = new StorageSpace();
        space.setPath(path);
        refreshSpace(space);
        return new long[]{space.getCapacity(), space.getUsedSpace(), space.getFreeSpace()};
    }
}
