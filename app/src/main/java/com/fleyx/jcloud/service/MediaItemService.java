package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.model.dto.MediaMatchUpdateDto;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.dto.MediaProgressUpdateDto;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesVo;

import java.util.List;

/**
 * 媒体条目查询与匹配服务。
 */
public interface MediaItemService {

    /**
     * 分页查询电影条目（海报墙）。
     *
     * @param userId 用户 ID
     * @param query  分页/搜索/排序参数
     * @return 电影条目分页
     */
    IPage<MediaItemVo> listMovies(String userId, MediaPageQueryDto query);

    /**
     * 分页查询电视剧列表（海报墙，按剧）。
     *
     * @param userId 用户 ID
     * @param query  分页/搜索/排序参数
     * @return 电视剧分页
     */
    IPage<MediaSeriesVo> listSeries(String userId, MediaPageQueryDto query);

    /**
     * 查询某部剧的所有剧集。
     *
     * @param seriesId 电视剧 ID
     * @param userId   用户 ID
     * @return 剧集条目列表
     */
    List<MediaItemVo> listEpisodes(String seriesId, String userId);

    /**
     * 分页查询其他类型条目。
     *
     * @param userId 用户 ID
     * @param query  分页/搜索/排序参数
     * @return 条目分页
     */
    IPage<MediaItemVo> listOthers(String userId, MediaPageQueryDto query);

    /**
     * 手动修正条目匹配。
     *
     * @param itemId 条目 ID
     * @param dto    入参
     * @param userId 用户 ID
     * @return 更新后的条目视图
     */
    MediaItemVo updateMatch(String itemId, MediaMatchUpdateDto dto, String userId);

    /**
     * 对整部剧批量修正匹配。
     *
     * @param seriesName 剧名
     * @param dto        入参
     * @param userId     用户 ID
     */
    void updateSeriesMatch(String seriesName, MediaMatchUpdateDto dto, String userId);

    /**
     * 上报播放进度。
     *
     * @param itemId 条目 ID
     * @param dto    入参
     * @param userId 用户 ID
     */
    void updateProgress(String itemId, MediaProgressUpdateDto dto, String userId);

    /**
     * 查询条目详情（详情页）。
     *
     * @param itemId 条目 ID
     * @param userId 用户 ID
     * @return 条目详情
     */
    MediaItemDetailVo getItemDetail(String itemId, String userId);

    /**
     * 查询电视剧详情（详情页，含剧集列表）。
     *
     * @param seriesId 电视剧 ID
     * @param userId   用户 ID
     * @return 电视剧详情
     */
    MediaSeriesDetailVo getSeriesDetail(String seriesId, String userId);
}
