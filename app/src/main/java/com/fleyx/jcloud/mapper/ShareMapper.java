package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.Share;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 分享主表数据访问层。
 */
@Mapper
public interface ShareMapper extends BaseMapper<Share> {

    /**
     * 根据短码查询未删除的分享。
     *
     * @param shareCode 短码
     * @return 分享实体
     */
    Share selectByShareCode(@Param("shareCode") String shareCode);
}
