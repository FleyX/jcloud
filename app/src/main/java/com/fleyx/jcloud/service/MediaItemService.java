package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.dto.MediaMatchUpdateDto;
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
     * 查询电影条目（海报墙）。
     *
     * @param userId 用户 ID
     * @return 电影条目列表
     */
    List<MediaItemVo> listMovies(String userId);

    /**
     * 查询电视剧聚合列表（海报墙）。
     *
     * @param userId 用户 ID
     * @return 剧集列表
     */
    List<MediaSeriesVo> listSeries(String userId);

    /**
     * 查询某部剧的所有剧集。
     *
     * @param seriesName 剧名
     * @param userId     用户 ID
     * @return 剧集条目列表
     */
    List<MediaItemVo> listEpisodes(String seriesName, String userId);

    /**
     * 查询其他类型条目。
     *
     * @param userId 用户 ID
     * @return 条目列表
     */
    List<MediaItemVo> listOthers(String userId);

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
     * @param seriesName 剧名
     * @param userId     用户 ID
     * @return 电视剧详情
     */
    MediaSeriesDetailVo getSeriesDetail(String seriesName, String userId);
}
