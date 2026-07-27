package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.service.TmdbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动后异步补抓存量元数据缺失的 TMDB 背景图。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaMetadataBackfillRunner implements ApplicationRunner {

    private final TmdbService tmdbService;

    @Override
    public void run(ApplicationArguments args) {
        Thread.startVirtualThread(() -> {
            try {
                tmdbService.backfillMissingBackdrops();
            } catch (Exception e) {
                log.warn("背景图补抓任务异常: {}", e.getMessage());
            }
        });
    }
}
