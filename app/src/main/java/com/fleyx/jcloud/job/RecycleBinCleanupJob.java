package com.fleyx.jcloud.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.RecycleRecordMapper;
import com.fleyx.jcloud.model.dto.FilePermanentDeleteDto;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.service.FileRecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回收站自动清理任务。
 * <p>
 * 每天凌晨 2 点执行，清理超过 30 天的回收站记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecycleBinCleanupJob {

    private static final int RETENTION_DAYS = 30;

    private final RecycleRecordMapper recycleRecordMapper;
    private final FileRecycleService fileRecycleService;

    /**
     * 每天凌晨 2 点执行清理。
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanup() {
        log.info("开始回收站自动清理任务");
        LocalDateTime expireTime = LocalDateTime.now().minusDays(RETENTION_DAYS);
        LambdaQueryWrapper<RecycleRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.lt(RecycleRecord::getCreateTime, expireTime);
        List<RecycleRecord> records = recycleRecordMapper.selectList(wrapper);
        if (records.isEmpty()) {
            log.info("回收站无过期记录，清理结束");
            return;
        }

        int success = 0;
        int failed = 0;
        for (RecycleRecord record : records) {
            try {
                fileRecycleService.permanentDelete(buildDto(record.getId()), record.getUserId());
                success++;
            } catch (BusinessException e) {
                failed++;
                log.warn("回收站记录清理失败: recordId={}, userId={}, message={}",
                        record.getId(), record.getUserId(), e.getMessage());
            } catch (Exception e) {
                failed++;
                log.error("回收站记录清理异常: recordId={}, userId={}", record.getId(), record.getUserId(), e);
            }
        }
        log.info("回收站自动清理完成: 成功={}, 失败={}", success, failed);
    }

    private FilePermanentDeleteDto buildDto(Long recordId) {
        FilePermanentDeleteDto dto = new FilePermanentDeleteDto();
        dto.setIds(List.of(recordId));
        return dto;
    }
}
