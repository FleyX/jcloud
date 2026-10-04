package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.event.NotificationEvent;
import com.fleyx.jcloud.mapper.NotificationMapper;
import com.fleyx.jcloud.model.po.Notification;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.util.IdUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 站内通知渠道（ADR 0039）：按解析出的收件人集合逐人落库。
 * <p>
 * 每用户仅保留最近 {@value #MAX_PER_USER} 条，插入后裁剪该用户超出的最旧记录（物理删除）。
 */
@Component
@RequiredArgsConstructor
public class InAppNotificationChannel implements NotificationChannel {

    /**
     * 每用户保留的通知条数上限。
     */
    public static final int MAX_PER_USER = 100;

    /**
     * 未读标记：未读。
     */
    private static final int UNREAD = 0;

    private final NotificationMapper notificationMapper;
    private final NotificationRecipientSupport recipientSupport;

    @Override
    public void deliver(NotificationEvent event) {
        for (User user : recipientSupport.resolve(event)) {
            insertForUser(user.getId(), event);
        }
    }

    private void insertForUser(String userId, NotificationEvent event) {
        Notification notification = new Notification();
        notification.setId(IdUtil.nextId());
        notification.setUserId(userId);
        notification.setEventType(event.getEventType().getValue());
        notification.setTitle(event.getTitle());
        notification.setContent(event.getContent());
        notification.setIsRead(UNREAD);
        notificationMapper.insert(notification);
        trimOverflow(userId);
    }

    /**
     * 裁剪超出上限的最旧记录：按创建时间倒序（同秒按 ID 倒序）保留最近 MAX_PER_USER 条。
     *
     * @param userId 收件用户 ID
     */
    private void trimOverflow(String userId) {
        List<Notification> latest = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .select(Notification::getId)
                .eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreateTime)
                .orderByDesc(Notification::getId));
        if (latest.size() <= MAX_PER_USER) {
            return;
        }
        List<String> expiredIds = latest.subList(MAX_PER_USER, latest.size()).stream()
                .map(Notification::getId)
                .toList();
        notificationMapper.deleteBatchIds(expiredIds);
    }
}
