package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.UserMigrationTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户存储空间迁移任务 Mapper。
 */
@Mapper
public interface UserMigrationTaskMapper extends BaseMapper<UserMigrationTask> {
}
