package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.Notification;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站内通知 Mapper。
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}
