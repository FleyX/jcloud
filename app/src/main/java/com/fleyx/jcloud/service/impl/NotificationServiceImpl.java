package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.NotificationMapper;
import com.fleyx.jcloud.model.po.Notification;
import com.fleyx.jcloud.model.vo.NotificationVo;
import com.fleyx.jcloud.service.NotificationService;
import com.fleyx.jcloud.service.support.InAppNotificationChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 站内通知服务实现。
 */
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    /**
     * 列表返回的最近通知条数上限，与站内渠道每用户保留条数同源，保证「查看全部」可见全部留存通知。
     */
    private static final int LIST_LIMIT = InAppNotificationChannel.MAX_PER_USER;

    /**
     * 已读 / 未读标记。
     */
    private static final int READ = 1;
    private static final int UNREAD = 0;

    private final NotificationMapper notificationMapper;

    @Override
    public List<NotificationVo> listRecent(String userId) {
        List<Notification> list = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreateTime)
                .orderByDesc(Notification::getId)
                .last("LIMIT " + LIST_LIMIT));
        return list.stream().map(this::toVo).toList();
    }

    @Override
    public long countUnread(String userId) {
        Long count = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, UNREAD));
        return count == null ? 0L : count;
    }

    @Override
    public void markRead(String id, String userId) {
        Notification existing = notificationMapper.selectById(id);
        if (existing == null || !userId.equals(existing.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "通知不存在");
        }
        if (existing.getIsRead() != null && existing.getIsRead() == READ) {
            return;
        }
        Notification update = new Notification();
        update.setId(id);
        update.setIsRead(READ);
        notificationMapper.updateById(update);
    }

    @Override
    public void markAllRead(String userId) {
        Notification update = new Notification();
        update.setIsRead(READ);
        notificationMapper.update(update, new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, UNREAD));
    }

    private NotificationVo toVo(Notification notification) {
        NotificationVo vo = new NotificationVo();
        vo.setId(notification.getId());
        vo.setEventType(notification.getEventType());
        vo.setTitle(notification.getTitle());
        vo.setContent(notification.getContent());
        vo.setIsRead(notification.getIsRead() != null && notification.getIsRead() == READ);
        vo.setCreateTime(notification.getCreateTime());
        return vo;
    }
}
