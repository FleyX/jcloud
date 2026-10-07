package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.model.vo.NotificationVo;
import com.fleyx.jcloud.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 站内通知控制器：当前用户的通知列表、未读数与已读流转。
 */
@RestController
@RequestMapping(CommonConstant.API + "/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 查询当前用户最近通知（倒序，最多 50 条）。
     *
     * @return 通知列表
     */
    @GetMapping
    public R<List<NotificationVo>> list() {
        return R.ok(notificationService.listRecent(UserContext.get().id()));
    }

    /**
     * 查询当前用户未读通知数。
     *
     * @return 未读数
     */
    @GetMapping("/unread-count")
    public R<Long> unreadCount() {
        return R.ok(notificationService.countUnread(UserContext.get().id()));
    }

    /**
     * 标记单条通知已读。
     *
     * @param id 通知 ID
     * @return 空结果
     */
    @PutMapping("/{id}/read")
    public R<Void> markRead(@PathVariable String id) {
        notificationService.markRead(id, UserContext.get().id());
        return R.ok();
    }

    /**
     * 标记全部通知已读。
     *
     * @return 空结果
     */
    @PutMapping("/read-all")
    public R<Void> markAllRead() {
        notificationService.markAllRead(UserContext.get().id());
        return R.ok();
    }
}
