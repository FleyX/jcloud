package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.MediaSeasonV2;
import org.apache.ibatis.annotations.Mapper;

/**
 * 电视剧季 Mapper（expand 阶段并行新表 t_media_season_v2）。
 */
@Mapper
public interface MediaSeasonV2Mapper extends BaseMapper<MediaSeasonV2> {
}
