package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.model.dto.NotificationLogPageQueryDto;
import com.fleyx.jcloud.model.vo.NotificationLogVo;

/**
 * 邮件发送记录服务：管理端分页查询发送记录。
 */
public interface NotificationLogService {

    /**
     * 分页查询发送记录，按发送时间倒序。
     *
     * @param dto 分页查询参数
     * @return 发送记录分页
     */
    IPage<NotificationLogVo> page(NotificationLogPageQueryDto dto);
}
