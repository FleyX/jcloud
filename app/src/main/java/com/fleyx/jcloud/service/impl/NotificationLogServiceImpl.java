package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.enums.NotificationEventType;
import com.fleyx.jcloud.mapper.NotificationLogMapper;
import com.fleyx.jcloud.model.dto.NotificationLogPageQueryDto;
import com.fleyx.jcloud.model.po.NotificationLog;
import com.fleyx.jcloud.model.vo.NotificationLogVo;
import com.fleyx.jcloud.service.NotificationLogService;
import com.fleyx.jcloud.service.support.NotificationLogSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 邮件发送记录服务实现。
 */
@Service
@RequiredArgsConstructor
public class NotificationLogServiceImpl implements NotificationLogService {

    private final NotificationLogMapper notificationLogMapper;

    @Override
    public IPage<NotificationLogVo> page(NotificationLogPageQueryDto dto) {
        LambdaQueryWrapper<NotificationLog> wrapper = new LambdaQueryWrapper<NotificationLog>()
                .orderByDesc(NotificationLog::getCreateTime)
                .orderByDesc(NotificationLog::getId);
        Page<NotificationLog> page = new Page<>(dto.getPageNum(), dto.getPageSize());
        return notificationLogMapper.selectPage(page, wrapper).convert(this::toVo);
    }

    private NotificationLogVo toVo(NotificationLog log) {
        NotificationLogVo vo = new NotificationLogVo();
        vo.setId(log.getId());
        vo.setEventType(log.getEventType());
        vo.setEventTypeName(NotificationEventType.fromValue(log.getEventType())
                .map(NotificationEventType::getDisplayName)
                .orElse(log.getEventType()));
        vo.setRecipient(log.getRecipient());
        vo.setSubject(log.getSubject());
        vo.setSuccess(log.getSuccess() != null && log.getSuccess() == NotificationLogSupport.SUCCESS);
        vo.setErrorMessage(log.getErrorMessage());
        vo.setCreateTime(log.getCreateTime());
        return vo;
    }
}
