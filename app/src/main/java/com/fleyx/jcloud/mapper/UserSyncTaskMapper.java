package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.UserSyncTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户存储空间同步任务数据访问层。
 */
@Mapper
public interface UserSyncTaskMapper extends BaseMapper<UserSyncTask> {

    /**
     * 根据用户 ID 查询最新一条同步任务。
     *
     * @param userId 用户 ID
     * @return 最新同步任务，不存在返回 null
     */
    UserSyncTask selectLatestByUserId(@Param("userId") String userId);
}
