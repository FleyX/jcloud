package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.model.po.MediaItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 媒体条目 Mapper。
 */
@Mapper
public interface MediaItemMapper extends BaseMapper<MediaItem> {

    /**
     * 分页查询媒体条目（关联文件名与元数据，支持关键词、媒体库过滤与排序）。
     *
     * @param page        分页参数
     * @param userId      用户 ID
     * @param itemType    条目类型：movie / episode / other
     * @param keyword     搜索关键词，可为空
     * @param directoryId 媒体库 ID 过滤，可为空
     * @param sortField   排序字段：added 添加时间 / release 发行时间
     * @param asc         是否升序
     * @return 分页结果
     */
    IPage<MediaItem> selectItemPage(Page<MediaItem> page, @Param("userId") String userId,
                                    @Param("itemType") String itemType, @Param("keyword") String keyword,
                                    @Param("directoryId") String directoryId,
                                    @Param("sortField") String sortField, @Param("asc") boolean asc);

    /**
     * 查询在播条目（有播放进度且未看完），按最近播放时间倒序。
     * <p>
     * 时长为空或为 0 无法计算看完阈值时，有进度即视为未看完。
     *
     * @param userId 用户 ID
     * @param limit  最大条数
     * @return 在播条目列表
     */
    List<MediaItem> selectInProgressItems(@Param("userId") String userId, @Param("limit") int limit);

    /**
     * 查询媒体库内最新添加且元数据有海报的条目（媒体库封面）。
     *
     * @param directoryId 媒体库 ID
     * @return 条目，无则返回 null
     */
    MediaItem selectLatestPosterItem(@Param("directoryId") String directoryId);
}
