package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.UserSyncConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户存储空间同步配置数据访问层。
 */
@Mapper
public interface UserSyncConfigMapper extends BaseMapper<UserSyncConfig> {

    /**
     * 查询已启用且下次同步时间已到的配置列表。
     *
     * @param now 当前时间
     * @return 配置列表
     */
    List<UserSyncConfig> selectEnabledDue(@Param("now") LocalDateTime now);
}
