package com.fleyx.jcloud.job;

import com.fleyx.jcloud.service.support.FolderSizeRecalcSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文件夹大小对账任务（票据 03 兜底）。
 * <p>
 * 启动时全量对账 + 每日凌晨 3 点定时对账（与回收站清理 2 点错开）。对账实际执行在
 * {@link FolderSizeRecalcSupport#reconcileAll()}（@Async，跨 bean 调用代理生效），
 * 不阻塞启动与调度线程。
 */
@Slf4j
@Component
@Order(300)
@RequiredArgsConstructor
public class FolderSizeReconcileJob implements ApplicationRunner {

    private final FolderSizeRecalcSupport folderSizeRecalcSupport;

    @Override
    public void run(ApplicationArguments args) {
        log.info("启动文件夹大小全量对账");
        folderSizeRecalcSupport.reconcileAll();
    }

    /**
     * 每天凌晨 3 点执行全量对账。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void reconcile() {
        log.info("开始每日文件夹大小对账");
        folderSizeRecalcSupport.reconcileAll();
    }
}
