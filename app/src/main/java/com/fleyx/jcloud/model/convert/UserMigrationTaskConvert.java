package com.fleyx.jcloud.model.convert;

import com.fleyx.jcloud.model.po.UserMigrationTask;
import com.fleyx.jcloud.model.vo.UserMigrationTaskVo;
import org.mapstruct.Mapper;

/**
 * 用户存储空间迁移任务对象转换器。
 */
@Mapper(componentModel = "spring")
public interface UserMigrationTaskConvert {

    /**
     * PO 转 VO。
     *
     * @param po 迁移任务实体
     * @return 迁移任务视图
     */
    UserMigrationTaskVo poToVo(UserMigrationTask po);
}
