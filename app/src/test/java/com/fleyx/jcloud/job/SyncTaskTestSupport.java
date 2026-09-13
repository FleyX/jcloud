package com.fleyx.jcloud.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.fleyx.jcloud.common.enums.SyncTaskStatus;
import com.fleyx.jcloud.common.enums.SyncTaskType;
import com.fleyx.jcloud.model.po.SyncTaskFields;
import com.fleyx.jcloud.util.IdUtil;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 同步任务调度执行测试共用辅助：PENDING 任务夹具构造、按归属计数与终态轮询。
 * <p>
 * 供 {@link RemoteMountSchedulerExecutionTest} 与 {@link UserSyncSchedulerExecutionTest} 复用，
 * 避免异步执行链路用例间复制等待/夹具样板。
 */
final class SyncTaskTestSupport {

    private SyncTaskTestSupport() {
    }

    /**
     * 构造并插入 PENDING 状态、计数清零的定时同步任务夹具（归属字段等由 customizer 在插入前设置）。
     *
     * @param mapper     任务 Mapper
     * @param supplier   任务 PO 构造器
     * @param customizer 插入前的任务定制（如设置归属 ID）
     * @param <T>        任务类型
     * @return 已插入的待执行任务
     */
    static <T extends SyncTaskFields> T insertPendingTask(BaseMapper<T> mapper, Supplier<T> supplier,
                                                          Consumer<T> customizer) {
        T task = supplier.get();
        task.setId(IdUtil.nextId());
        task.setType(SyncTaskType.SCHEDULED.getValue());
        task.setStatus(SyncTaskStatus.PENDING.getValue());
        task.setTotalCount(0L);
        task.setSuccessCount(0L);
        task.setFailCount(0L);
        customizer.accept(task);
        mapper.insert(task);
        return task;
    }

    /**
     * 按归属列统计任务数（「重发不新建任务记录」断言用）。
     *
     * @param mapper      任务 Mapper
     * @param ownerColumn 归属字段
     * @param ownerId     归属 ID
     * @param <T>         任务类型
     * @return 任务数
     */
    static <T> long countTasksByOwner(BaseMapper<T> mapper, SFunction<T, ?> ownerColumn, String ownerId) {
        return mapper.selectCount(new LambdaQueryWrapper<T>().eq(ownerColumn, ownerId));
    }

    /**
     * 轮询任务直到到达期望状态（100ms × 最多 100 次 = 10s 超时），超时返回最后一次观测值。
     *
     * @param mapper         任务 Mapper
     * @param taskId         任务 ID
     * @param expectedStatus 期望到达的任务状态
     * @param <T>            任务类型
     * @return 最后一次观测到的任务记录
     */
    static <T extends SyncTaskFields> T awaitTaskStatus(BaseMapper<T> mapper, String taskId,
                                                        String expectedStatus) throws Exception {
        T task = null;
        for (int i = 0; i < 100; i++) {
            task = mapper.selectById(taskId);
            if (task != null && expectedStatus.equals(task.getStatus())) {
                return task;
            }
            Thread.sleep(100);
        }
        return task;
    }
}
