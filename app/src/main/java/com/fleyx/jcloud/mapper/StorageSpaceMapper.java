package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.StorageSpace;
import org.apache.ibatis.annotations.Mapper;

/**
 * 存储空间数据访问层。
 */
@Mapper
public interface StorageSpaceMapper extends BaseMapper<StorageSpace> {
}
