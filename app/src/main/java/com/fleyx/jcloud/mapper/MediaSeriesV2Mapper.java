package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.MediaSeriesV2;
import org.apache.ibatis.annotations.Mapper;

/**
 * 剧集 Mapper（expand 阶段并行新表 t_media_series_v2）。
 */
@Mapper
public interface MediaSeriesV2Mapper extends BaseMapper<MediaSeriesV2> {
}
