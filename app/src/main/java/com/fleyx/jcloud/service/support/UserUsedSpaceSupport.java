package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.NotificationEventType;
import com.fleyx.jcloud.common.enums.NotificationTargetType;
import com.fleyx.jcloud.common.event.NotificationEvent;
import com.fleyx.jcloud.common.event.SyncCompletedEvent;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 用户已用空间集中记账支撑组件。
 * <p>
 * 本组件是全项目唯一允许写 {@code t_user.used_space} 的入口（逻辑口径，见 ADR-0027）。
 * 后续所有写路径（上传、删除、回收站、恢复等）统一经此记账，避免各处散落的
 * 读-改-写更新互相覆盖。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserUsedSpaceSupport {

    /**
     * 配额/容量告警阈值：使用率达到 80% 即告警。
     */
    private static final double ALERT_THRESHOLD = 0.8;

    private final UserMapper userMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final NotificationAlertDedupSupport alertDedupSupport;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 按增量原子累加用户已用空间（单语句 UPDATE，并发调用不丢更新，结果不小于 0）。
     * <p>
     * 累加后检查配额与所属存储空间容量是否达到告警阈值（80%），达阈值且 24 小时内未告警过则发送通知。
     *
     * @param userId 用户 ID
     * @param delta  容量增量（可为负）
     */
    public void addUsedSpace(String userId, long delta) {
        userMapper.addUsedSpace(userId, delta);
        checkUsageAlert(userId);
    }

    /**
     * 检查用户配额与所属存储空间容量是否达到告警阈值并触发通知。
     * <p>
     * 阈值比较用 {@code >= 0.8}；配额或容量为 0/null 视为不限，跳过。两条告警分别以用户与
     * 存储空间为对象独立去重（24 小时窗口），仅首次超限发送。本方法只做读取与 Redis 标记，
     * 事件由通知模块异步落库/发信，不阻塞记账路径。
     *
     * @param userId 用户 ID
     */
    private void checkUsageAlert(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return;
        }
        checkQuotaAlert(user);
        checkCapacityAlert(user);
    }

    private void checkQuotaAlert(User user) {
        Long quota = user.getQuota();
        long used = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        if (quota == null || quota <= 0 || used < quota * ALERT_THRESHOLD) {
            return;
        }
        if (!alertDedupSupport.tryMark("quota:" + user.getId())) {
            return;
        }
        long percent = Math.round(used * 100.0 / quota);
        eventPublisher.publishEvent(new NotificationEvent(this, NotificationEventType.QUOTA_ALERT,
                user.getId(), "配额告警",
                String.format("已用空间 %d 字节，配额 %d 字节，使用率已达 %d%%", used, quota, percent)));
        eventPublisher.publishEvent(new NotificationEvent(this, NotificationEventType.QUOTA_ALERT,
                NotificationTargetType.ADMINS, null, "配额告警",
                String.format("用户 %s 已用空间 %d 字节，配额 %d 字节，使用率已达 %d%%",
                        user.getUsername(), used, quota, percent)));
    }

    private void checkCapacityAlert(User user) {
        String spaceId = user.getStorageSpaceId();
        if (spaceId == null) {
            return;
        }
        StorageSpace space = storageSpaceMapper.selectById(spaceId);
        if (space == null) {
            return;
        }
        Long capacity = space.getCapacity();
        long used = space.getUsedSpace() == null ? 0L : space.getUsedSpace();
        if (capacity == null || capacity <= 0 || used < capacity * ALERT_THRESHOLD) {
            return;
        }
        if (!alertDedupSupport.tryMark("capacity:" + spaceId)) {
            return;
        }
        long percent = Math.round(used * 100.0 / capacity);
        eventPublisher.publishEvent(new NotificationEvent(this, NotificationEventType.STORAGE_CAPACITY_ALERT,
                NotificationTargetType.ADMINS, null, "存储空间容量告警",
                String.format("存储空间「%s」已用 %d 字节，容量 %d 字节，使用率已达 %d%%",
                        space.getName(), used, capacity, percent)));
    }

    /**
     * 按逻辑口径全量重算用户已用空间并落库，返回重算后的最新值。
     * <p>
     * 口径：该用户全部本地文件节点（type=file、source_type=local）size 之和
     * + 回收站记录 total_size 之和；远程节点与文件夹节点不计入（回收站保留期内继续占用）。
     *
     * @param userId 用户 ID
     * @return 重算后的已用空间（用户不存在时为 0）
     */
    public long recalcUsedSpace(String userId) {
        userMapper.recalcUsedSpace(userId);
        User user = userMapper.selectById(userId);
        return user == null || user.getUsedSpace() == null ? 0L : user.getUsedSpace();
    }

    /**
     * 监听同步完成事件：用户存储空间同步整轮落库后按逻辑口径全量重算该用户已用空间。
     * <p>
     * 同步执行器内部不做逐文件记账，由本监听兜底刷新真实值（事件在 COMPLETED/PARTIAL
     * 后发布，FAILED 不发布）；远程挂载同步不改变本地物理占用，忽略（分支结构与
     * {@link FolderSizeRecalcSupport#onSyncCompleted} 一致）。异步执行，不阻塞同步任务本身。
     */
    @Async
    @EventListener
    public void onSyncCompleted(SyncCompletedEvent event) {
        if (SyncCompletedEvent.TYPE_USER.equals(event.getSyncType())) {
            User user = userMapper.selectById(event.getUserId());
            long before = user == null || user.getUsedSpace() == null ? 0L : user.getUsedSpace();
            long after = recalcUsedSpace(event.getUserId());
            log.info("用户同步完成触发已用空间重算: userId={}, before={}, after={}", event.getUserId(), before, after);
        }
    }
}
