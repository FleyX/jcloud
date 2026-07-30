package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 媒体目录任务互斥支撑组件。
 * <p>
 * 同一目录同一时间只允许一个任务（扫描 scan / 削刮 scrape）执行，不同目录互不阻塞。
 * 基于内存状态实现，服务重启后任务自然终止、锁自然释放。
 */
@Component
public class MediaTaskSupport {

    /**
     * 任务类型：扫描。
     */
    public static final String TASK_SCAN = "scan";

    /**
     * 任务类型：削刮。
     */
    public static final String TASK_SCRAPE = "scrape";

    /**
     * 正在执行任务的目录（directoryId → 任务类型）。
     */
    private final Map<String, String> activeTasks = new ConcurrentHashMap<>();

    /**
     * 已请求取消的目录 ID 集合（扫描/削刮通用）。
     */
    private final Set<String> cancelledTasks = ConcurrentHashMap.newKeySet();

    /**
     * 尝试占用目录执行任务。
     *
     * @param directoryId 目录 ID
     * @param taskType    任务类型
     * @return 是否成功占用；false 表示该目录已有任务在执行
     */
    public boolean enter(String directoryId, String taskType) {
        return activeTasks.putIfAbsent(directoryId, taskType) == null;
    }

    /**
     * 占用目录执行任务，已被占用则抛出业务异常。
     *
     * @param directoryId 目录 ID
     * @param taskType    任务类型
     */
    public void enterOrThrow(String directoryId, String taskType) {
        if (!enter(directoryId, taskType)) {
            String running = activeTasks.get(directoryId);
            throw new BusinessException(ResultCode.BUSINESS_ERROR,
                    TASK_SCRAPE.equals(running) ? "该目录正在削刮中，请稍后重试" : "该目录正在扫描中，请稍后重试");
        }
    }

    /**
     * 释放目录任务占用。
     *
     * @param directoryId 目录 ID
     * @param taskType    任务类型（仅释放属于自己的占用）
     */
    public void exit(String directoryId, String taskType) {
        activeTasks.remove(directoryId, taskType);
    }

    /**
     * 请求取消目录当前任务（协作式，任务循环逐条检查）。
     *
     * @param directoryId 目录 ID
     */
    public void requestCancel(String directoryId) {
        cancelledTasks.add(directoryId);
    }

    /**
     * 任务是否被请求取消。
     *
     * @param directoryId 目录 ID
     * @return 是否已请求取消
     */
    public boolean isCancelled(String directoryId) {
        return cancelledTasks.contains(directoryId);
    }

    /**
     * 清除取消标记（任务开始时调用）。
     *
     * @param directoryId 目录 ID
     */
    public void clearCancel(String directoryId) {
        cancelledTasks.remove(directoryId);
    }
}
