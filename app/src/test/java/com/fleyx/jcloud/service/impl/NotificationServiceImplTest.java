package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.enums.NotificationEventType;
import com.fleyx.jcloud.common.event.NotificationEvent;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.NotificationMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.Notification;
import com.fleyx.jcloud.model.vo.NotificationVo;
import com.fleyx.jcloud.service.NotificationService;
import com.fleyx.jcloud.service.support.InAppNotificationChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 站内通知服务集成测试（工单 02）。
 * <p>
 * 事件监听为异步执行，事件落库用例使用轮询（100ms 间隔、10s 超时）；
 * 不使用事务回滚（异步线程看不到未提交数据），测试数据按唯一用户隔离并在用例结束后物理清理。
 */
class NotificationServiceImplTest extends IntegrationTestBase {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private InAppNotificationChannel inAppNotificationChannel;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StorageSpaceMapper storageSpaceMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private final List<String> createdUserIds = new ArrayList<>();
    private final List<String> createdSpaceIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String userId : createdUserIds) {
            notificationMapper.delete(new LambdaQueryWrapper<Notification>().eq(Notification::getUserId, userId));
            userMapper.deleteById(userId);
        }
        createdUserIds.clear();
        createdSpaceIds.forEach(storageSpaceMapper::deleteById);
        createdSpaceIds.clear();
    }

    /**
     * 发布通知事件后经站内渠道异步落库：字段与事件一致，默认未读。
     */
    @Test
    void shouldPersistInAppNotificationWhenEventPublished() {
        String userId = prepareUser().user().getId();

        eventPublisher.publishEvent(new NotificationEvent(this, NotificationEventType.TRANSFER_COMPLETED,
                userId, "跨来源传输完成", "复制任务完成：成功 3 个，失败 0 个"));

        NotificationVo vo = awaitLatestNotification(userId);
        assertEquals(NotificationEventType.TRANSFER_COMPLETED.getValue(), vo.getEventType());
        assertEquals("跨来源传输完成", vo.getTitle());
        assertEquals("复制任务完成：成功 3 个，失败 0 个", vo.getContent());
        assertFalse(vo.getIsRead(), "新通知应为未读");
        assertEquals(1L, notificationService.countUnread(userId));
    }

    /**
     * 已读状态流转：单条已读后未读数下降，全部已读后清零。
     */
    @Test
    void shouldFlowReadStateForSingleAndAll() {
        String userId = prepareUser().user().getId();
        deliver(userId, "通知一");
        deliver(userId, "通知二");
        assertEquals(2L, notificationService.countUnread(userId));

        List<NotificationVo> list = notificationService.listRecent(userId);
        assertEquals(2, list.size());
        notificationService.markRead(list.get(0).getId(), userId);
        assertEquals(1L, notificationService.countUnread(userId));
        assertTrue(notificationService.listRecent(userId).get(0).getIsRead(), "被标记的通知应为已读");

        notificationService.markAllRead(userId);
        assertEquals(0L, notificationService.countUnread(userId));
        assertTrue(notificationService.listRecent(userId).stream().allMatch(NotificationVo::getIsRead));
    }

    /**
     * 100 条上限：写入 105 条后仅保留最近 100 条，最旧的 5 条被物理删除。
     */
    @Test
    void shouldTrimOldestNotificationsBeyondHundred() {
        String userId = prepareUser().user().getId();
        for (int i = 1; i <= 105; i++) {
            deliver(userId, "通知-" + i);
        }

        List<Notification> remaining = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .orderByAsc(Notification::getCreateTime)
                .orderByAsc(Notification::getId));
        assertEquals(InAppNotificationChannel.MAX_PER_USER, remaining.size());
        assertTrue(remaining.stream().noneMatch(item -> item.getTitle().matches("通知-[1-5]")),
                "最旧的 5 条应被清理");
        List<NotificationVo> list = notificationService.listRecent(userId);
        assertEquals(50, list.size(), "列表仅返回最近 50 条");
        assertEquals("通知-105", list.get(0).getTitle());
    }

    /**
     * 跨用户隔离：A 的通知 B 不可见，B 也不能标记 A 的通知已读。
     */
    @Test
    void shouldIsolateNotificationsAcrossUsers() {
        String userA = prepareUser().user().getId();
        String userB = prepareUser().user().getId();
        deliver(userA, "A 的通知");

        assertTrue(notificationService.listRecent(userB).isEmpty());
        assertEquals(0L, notificationService.countUnread(userB));

        String notificationId = notificationService.listRecent(userA).get(0).getId();
        assertThrows(BusinessException.class, () -> notificationService.markRead(notificationId, userB));
        assertEquals(1L, notificationService.countUnread(userA), "越权标记不应改变 A 的未读数");
    }

    // ---------- 工具方法 ----------

    private UserWithSpace prepareUser() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        createdUserIds.add(userWithSpace.user().getId());
        createdSpaceIds.add(userWithSpace.space().getId());
        return userWithSpace;
    }

    private void deliver(String userId, String title) {
        inAppNotificationChannel.deliver(new NotificationEvent(this, NotificationEventType.TRANSFER_FAILED,
                userId, title, title + " 的内容"));
    }

    private NotificationVo awaitLatestNotification(String userId) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            List<NotificationVo> list = notificationService.listRecent(userId);
            if (!list.isEmpty()) {
                return list.get(0);
            }
            if (System.currentTimeMillis() > deadline) {
                fail("通知未在超时内落库: userId=" + userId);
            }
            sleepQuietly();
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }
}
