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
 * 异步执行，不阻塞业务触发点；单个渠道失败不影响其余渠道，仅记录日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final List<NotificationChannel> channels;

    /**
     * 消费通知事件并分发至全部渠道。
     *
     * @param event 通知事件
     */
    @Async
    @EventListener
    public void onNotification(NotificationEvent event) {
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
