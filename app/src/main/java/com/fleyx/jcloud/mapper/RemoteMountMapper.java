package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.RemoteMount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 远程挂载配置数据访问层。
 */
@Mapper
public interface RemoteMountMapper extends BaseMapper<RemoteMount> {

    /**
     * 查询已到期的启用定时同步挂载点。
     *
     * @param now 当前时间
     * @return 到期挂载点列表
     */
    List<RemoteMount> selectEnabledDue(@Param("now") LocalDateTime now);
}
