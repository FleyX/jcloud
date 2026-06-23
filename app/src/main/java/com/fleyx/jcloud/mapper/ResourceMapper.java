package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.Resource;
import org.apache.ibatis.annotations.Mapper;

/**
 * 资源数据访问层。
 */
@Mapper
public interface ResourceMapper extends BaseMapper<Resource> {
}
