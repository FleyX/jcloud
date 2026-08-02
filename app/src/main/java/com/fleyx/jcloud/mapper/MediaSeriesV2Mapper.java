package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.model.po.MediaSeriesV2;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 剧集 Mapper（expand 阶段并行新表 t_media_series_v2）。
 */
@Mapper
public interface MediaSeriesV2Mapper extends BaseMapper<MediaSeriesV2> {

    /**
     * 分页查询电视剧（关联元数据，支持关键词、媒体库过滤与排序）。
     *
     * @param page        分页参数
     * @param userId      用户 ID
     * @param keyword     搜索关键词，可为空
     * @param directoryId 媒体库 ID 过滤（剧行库级归属），可为空
     * @param sortField   排序字段：added 添加时间 / release 发行时间
     * @param asc         是否升序
     * @return 分页结果
     */
    IPage<MediaSeriesV2> selectSeriesPage(Page<MediaSeriesV2> page, @Param("userId") String userId,
                                          @Param("keyword") String keyword, @Param("directoryId") String directoryId,
                                          @Param("sortField") String sortField, @Param("asc") boolean asc);
}
