package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.TransferTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 跨来源传输任务 Mapper。
 */
@Mapper
public interface TransferTaskMapper extends BaseMapper<TransferTask> {
}
