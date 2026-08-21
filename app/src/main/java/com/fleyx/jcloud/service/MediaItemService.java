package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.model.dto.MediaMatchUpdateDto;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.dto.MediaProgressUpdateDto;
import com.fleyx.jcloud.model.dto.MediaWatchedUpdateDto;
import com.fleyx.jcloud.model.vo.MediaGenreVo;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaSearchResultVo;
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
     * 全局搜索：跨该用户全部媒体库搜索，按电影/剧集/其他分组返回（各组前 N 条 + 总数）。
     * 搜索字段与墙内搜索一致：文件名/剧名/元数据标题/原始标题/简介。
     *
     * @param userId  用户 ID
     * @param keyword 搜索关键词
     * @param size    每组条数（内部收敛到 [1, 50]，默认 8 由调用方决定）
     * @return 分组搜索结果
     */
    MediaSearchResultVo search(String userId, String keyword, int size);

    /**
     * 手动修正条目匹配（统一按行 ID，issue #21）：id 为电影行或剧集行 ID；
     * 剧集行 ID 时整剧应用（等效原系列级修正语义）。集级手动修正已下线。
     *
     * @param itemId 条目行 ID（电影行或剧集行）
     * @param dto    入参
     * @param userId 用户 ID
     * @return 更新后的条目视图
     */
    MediaItemVo updateMatch(String itemId, MediaMatchUpdateDto dto, String userId);

    /**
     * 上报播放进度。
     *
     * @param itemId 条目 ID
     * @param dto    入参
     * @param userId 用户 ID
     */
    void updateProgress(String itemId, MediaProgressUpdateDto dto, String userId);

    /**
     * 标记/取消已观看（电影/集/其他标题级行）。标记已观看时清零播放进度，
     * 取消标记仅清除 watched 不动进度。
     *
     * @param itemId 条目 ID
     * @param dto    入参
     * @param userId 用户 ID
     */
    void updateWatched(String itemId, MediaWatchedUpdateDto dto, String userId);

    /**
     * 查询条目详情（详情页）。
     *
     * @param itemId 条目 ID
     * @param userId 用户 ID
     * @return 条目详情
     */
    MediaItemDetailVo getItemDetail(String itemId, String userId);

    /**
     * 查询电视剧详情（详情页，含季卡片列表，不含全量剧集）。
     *
     * @param seriesId 电视剧 ID
     * @param userId   用户 ID
     * @return 电视剧详情
     */
    MediaSeriesDetailVo getSeriesDetail(String seriesId, String userId);

    /**
     * 查询某部剧指定季的剧集列表（详情页按季懒加载）。
     *
     * @param seriesId 电视剧 ID
     * @param seasonId 季 ID
     * @param userId   用户 ID
     * @return 剧集条目列表（按集号升序）
     */
    List<MediaItemVo> listSeasonEpisodes(String seriesId, String seasonId, String userId);

    /**
     * 按文件节点 ID 反查媒体条目 ID。
     *
     * @param fileNodeId 文件节点 ID
     * @param userId     用户 ID
     * @return 媒体条目 ID
     * @throws BusinessException 未找到时抛出
     */
    String getItemIdByFileNodeId(String fileNodeId, String userId);

    /**
     * 聚合媒体库类型列表（类型页）。
     *
     * @param userId      用户 ID
     * @param directoryId 媒体库 ID
     * @return 类型列表（名称 + 条目数 + 代表海报），其他库返回空列表
     */
    List<MediaGenreVo> listGenres(String userId, String directoryId);
}
