package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.SystemConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统配置数据访问层。
 */
@Mapper
public interface SystemConfigMapper extends BaseMapper<SystemConfig> {
}
