package com.fleyx.jcloud.job;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.common.enums.MediaScanStatus;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.model.po.MediaDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时清理遗留的扫描中状态（服务重启导致扫描中断）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaScanStartupCleaner implements ApplicationRunner {

    private final MediaDirectoryMapper mediaDirectoryMapper;

    @Override
    public void run(ApplicationArguments args) {
        int updated = mediaDirectoryMapper.update(null, new LambdaUpdateWrapper<MediaDirectory>()
                .eq(MediaDirectory::getLastScanStatus, MediaScanStatus.SCANNING.name())
                .set(MediaDirectory::getLastScanStatus, MediaScanStatus.FAILED.name())
                .set(MediaDirectory::getLastScanError, "服务重启，扫描中断"));
        if (updated > 0) {
            log.info("重置遗留的扫描中状态目录数: {}", updated);
        }
    }
}
