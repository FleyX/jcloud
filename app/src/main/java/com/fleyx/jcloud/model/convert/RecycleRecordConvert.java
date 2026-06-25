package com.fleyx.jcloud.model.convert;

import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.vo.RecycleRecordVo;
import org.mapstruct.Mapper;

/**
 * 回收站记录对象转换器。
 */
@Mapper(componentModel = "spring")
public interface RecycleRecordConvert {

    /**
     * PO 转 VO。
     *
     * @param po 回收站记录实体
     * @return 回收站记录视图
     */
    RecycleRecordVo poToVo(RecycleRecord po);
}
