package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.RemoteSyncTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 远程同步任务数据访问层。
 */
@Mapper
public interface RemoteSyncTaskMapper extends BaseMapper<RemoteSyncTask> {

    /**
     * 查询指定挂载点的最新同步任务。
     *
     * @param remoteMountId 远程挂载 ID
     * @return 最新同步任务
     */
    RemoteSyncTask selectLatestByMountId(@Param("remoteMountId") String remoteMountId);
}
