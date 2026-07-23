package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.SyncTaskStatus;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.po.SyncTaskFields;
import com.fleyx.jcloud.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * 同步任务共享支撑组件。
 * <p>
 * 收敛远程挂载同步与用户存储空间同步（含迁移）中重复出现的
 * 任务状态回写、PENDING 任务构造、进行中任务判定与 cron 解析逻辑。
 */
@Slf4j
@Component
public class SyncTaskSupport {

    /**
     * 获取执行锁的最大等待秒数。
     */
    public static final long LOCK_WAIT_SECONDS = 30L;

    /**
     * 任务错误信息最大长度（与数据库列一致）。
     */
    public static final int MAX_ERROR_LENGTH = 4000;

    /**
     * 进行中（占用执行位）的任务状态集合。
     */
    public static final List<String> ACTIVE_STATUSES =
            List.of(SyncTaskStatus.PENDING.getValue(), SyncTaskStatus.RUNNING.getValue());

    /**
     * 构造 PENDING 状态的同步任务（计数清零）。
     *
     * @param supplier 任务 PO 构造器
     * @param type     触发方式
     * @param <T>      任务类型
     * @return 待执行任务
     */
    public <T extends SyncTaskFields> T buildPendingTask(Supplier<T> supplier, String type) {
        T task = supplier.get();
        task.setId(IdUtil.nextId());
        task.setType(type);
        task.setStatus(SyncTaskStatus.PENDING.getValue());
        task.setTotalCount(0L);
        task.setSuccessCount(0L);
        task.setFailCount(0L);
        return task;
    }

    /**
     * 将任务置为运行中。
     *
     * @param task   任务
     * @param mapper 任务 Mapper
     * @param <T>    任务类型
     */
    public <T extends SyncTaskFields> void markRunning(T task, BaseMapper<T> mapper) {
        task.setStatus(SyncTaskStatus.RUNNING.getValue());
        task.setStartTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        mapper.updateById(task);
    }

    /**
     * 按执行结果完成任务（有失败置 PARTIAL，否则 COMPLETED）。
     *
     * @param task    任务
     * @param context 执行上下文
     * @param mapper  任务 Mapper
     * @param <T>     任务类型
     */
    public <T extends SyncTaskFields> void completeTask(T task, SyncContext context, BaseMapper<T> mapper) {
        task.setStatus(context.getFailCount() > 0 ? SyncTaskStatus.PARTIAL.getValue()
                : SyncTaskStatus.COMPLETED.getValue());
        task.setEndTime(LocalDateTime.now());
        task.setTotalCount(context.getTotalCount());
        task.setSuccessCount(context.getSuccessCount());
        task.setFailCount(context.getFailCount());
        task.setErrorMsg(truncate(context.getErrorMessage()));
        task.setUpdateTime(LocalDateTime.now());
        mapper.updateById(task);
    }

    /**
     * 将任务置为失败。
     *
     * @param task     任务
     * @param errorMsg 错误信息
     * @param mapper   任务 Mapper
     * @param <T>      任务类型
     */
    public <T extends SyncTaskFields> void failTask(T task, String errorMsg, BaseMapper<T> mapper) {
        task.setStatus(SyncTaskStatus.FAILED.getValue());
        task.setEndTime(LocalDateTime.now());
        task.setErrorMsg(truncate(errorMsg));
        task.setUpdateTime(LocalDateTime.now());
        mapper.updateById(task);
    }

    /**
     * 判断指定归属下是否存在指定状态的任务。
     *
     * @param mapper       任务 Mapper
     * @param ownerColumn  归属字段
     * @param ownerId      归属 ID
     * @param statusColumn 状态字段
     * @param statuses     状态集合
     * @param <T>          任务类型
     * @return 是否存在
     */
    public <T> boolean hasActiveTask(BaseMapper<T> mapper, SFunction<T, ?> ownerColumn, String ownerId,
                                     SFunction<T, ?> statusColumn, Collection<String> statuses) {
        QueryWrapper<T> wrapper = new QueryWrapper<>();
        wrapper.eq("delete_at", 0L);
        wrapper.lambda().eq(ownerColumn, ownerId).in(statusColumn, statuses);
        return mapper.selectCount(wrapper) > 0;
    }

    /**
     * 截断错误信息至 {@link #MAX_ERROR_LENGTH}。
     *
     * @param errorMsg 错误信息
     * @return 截断后的错误信息
     */
    public String truncate(String errorMsg) {
        if (errorMsg == null) {
            return null;
        }
        return errorMsg.length() > MAX_ERROR_LENGTH ? errorMsg.substring(0, MAX_ERROR_LENGTH) : errorMsg;
    }

    /**
     * 严格解析 cron 表达式，格式错误抛出业务异常。
     *
     * @param cronExpr cron 表达式
     * @return cron 表达式对象
     */
    public CronExpression parseCron(String cronExpr) {
        try {
            return CronExpression.parse(cronExpr);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "cron 表达式格式错误: " + e.getMessage());
        }
    }

    /**
     * 宽松解析 cron 表达式，解析失败返回 {@code null} 并记录警告日志。
     *
     * @param cronExpr cron 表达式
     * @return cron 表达式对象，解析失败为 {@code null}
     */
    public CronExpression tryParseCron(String cronExpr) {
        try {
            return CronExpression.parse(cronExpr);
        } catch (Exception e) {
            log.warn("解析 cron 表达式失败，cronExpr={}", cronExpr, e);
            return null;
        }
    }
}
