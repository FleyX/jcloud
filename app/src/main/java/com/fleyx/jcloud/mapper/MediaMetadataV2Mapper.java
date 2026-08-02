package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.MediaMetadataV2;
import org.apache.ibatis.annotations.Mapper;

/**
 * 媒体元数据 Mapper（expand 阶段并行新表 t_media_metadata_v2）。
 */
@Mapper
public interface MediaMetadataV2Mapper extends BaseMapper<MediaMetadataV2> {
}
