package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.event.NotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通知事件监听器（ADR 0039）：遍历所有渠道适配器分发。
 * <p>
 * 异步执行，不阻塞业务触发点；事件开关禁用则整事件跳过（仅站内渠道的兜底事件不受开关抑制）；
 * 单个渠道失败不影响其余渠道，仅记录日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final List<NotificationChannel> channels;
    private final NotificationSwitchSupport notificationSwitchSupport;

    /**
     * 消费通知事件并分发至全部渠道。
     *
     * @param event 通知事件
     */
    @Async
    @EventListener
    public void onNotification(NotificationEvent event) {
        // 站内兜底类事件（如邮件分享失败）不受事件开关抑制，恒定投递
        if (!event.getEventType().isInAppOnly() && !notificationSwitchSupport.isEnabled(event.getEventType())) {
            log.debug("通知事件已禁用，跳过分发：eventType={}", event.getEventType());
            return;
        }
        for (NotificationChannel channel : channels) {
            try {
                channel.deliver(event);
            } catch (Exception e) {
                log.error("通知渠道投递失败，channel={}, eventType={}, targetUserId={}",
                        channel.getClass().getSimpleName(), event.getEventType(), event.getTargetUserId(), e);
            }
        }
    }
}
