package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.event.NotificationEvent;

/**
 * 通知渠道适配器（ADR 0039）。
 * <p>
 * 通知事件由监听器统一消费后遍历所有渠道调用，新增渠道只需新增实现，业务触发点不变。
 */
public interface NotificationChannel {

    /**
     * 投递通知事件。
     *
     * @param event 通知事件
     */
    void deliver(NotificationEvent event);
}
