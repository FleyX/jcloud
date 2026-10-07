package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.NotificationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 邮件发送记录 Mapper。
 */
@Mapper
public interface NotificationLogMapper extends BaseMapper<NotificationLog> {
}
