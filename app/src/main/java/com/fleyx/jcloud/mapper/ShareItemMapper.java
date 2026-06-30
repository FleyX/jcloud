package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.ShareItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 分享项数据访问层。
 */
@Mapper
public interface ShareItemMapper extends BaseMapper<ShareItem> {

    /**
     * 根据分享 ID 查询分享项列表。
     *
     * @param shareId 分享 ID
     * @return 分享项列表
     */
    List<ShareItem> selectByShareId(@Param("shareId") String shareId);

    /**
     * 根据分享 ID 删除全部分享项。
     *
     * @param shareId 分享 ID
     * @return 影响行数
     */
    int deleteByShareId(@Param("shareId") String shareId);
}
