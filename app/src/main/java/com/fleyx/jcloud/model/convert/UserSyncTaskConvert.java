package com.fleyx.jcloud.model.convert;

import com.fleyx.jcloud.model.po.UserSyncTask;
import com.fleyx.jcloud.model.vo.UserSyncTaskVo;
import org.mapstruct.Mapper;

/**
 * 用户存储空间同步任务对象转换器。
 */
@Mapper(componentModel = "spring")
public interface UserSyncTaskConvert {

    /**
     * PO 转 VO。
     *
     * @param po 同步任务实体
     * @return 同步任务视图
     */
    UserSyncTaskVo poToVo(UserSyncTask po);
}
