package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.RecycleRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 回收站记录数据访问层。
 */
@Mapper
public interface RecycleRecordMapper extends BaseMapper<RecycleRecord> {

    /**
     * 物理删除回收站记录。
     *
     * @param id 记录 ID
     * @return 影响行数
     */
    int physicalDeleteById(@Param("id") String id);
}
