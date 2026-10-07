package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.vo.NotificationVo;

import java.util.List;

/**
 * 站内通知服务：当前用户通知的查询与已读流转。
 */
public interface NotificationService {

    /**
     * 查询当前用户最近的通知（倒序，最多 50 条）。
     *
     * @param userId 用户 ID
     * @return 通知列表
     */
    List<NotificationVo> listRecent(String userId);

    /**
     * 统计当前用户未读通知数。
     *
     * @param userId 用户 ID
     * @return 未读数
     */
    long countUnread(String userId);

    /**
     * 标记单条通知已读（仅限本人通知）。
     *
     * @param id     通知 ID
     * @param userId 用户 ID
     */
    void markRead(String id, String userId);

    /**
     * 标记当前用户全部未读通知已读。
     *
     * @param userId 用户 ID
     */
    void markAllRead(String userId);
}
