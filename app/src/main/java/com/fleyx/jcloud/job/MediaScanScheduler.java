package com.fleyx.jcloud.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.service.MediaScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 媒体库定时重扫任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaScanScheduler {

    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final MediaScanService mediaScanService;

    /**
     * 每分钟检查到期的定时重扫目录。
     */
    @Scheduled(fixedRate = 60_000)
    public void scanDue() {
        List<MediaDirectory> due = mediaDirectoryMapper.selectList(new LambdaQueryWrapper<MediaDirectory>()
                .isNotNull(MediaDirectory::getNextScanTime)
                .le(MediaDirectory::getNextScanTime, LocalDateTime.now()));
        for (MediaDirectory directory : due) {
            log.info("触发媒体目录定时重扫: {}", directory.getId());
            mediaScanService.submitScan(directory.getId(), directory.getUserId());
        }
    }
}
