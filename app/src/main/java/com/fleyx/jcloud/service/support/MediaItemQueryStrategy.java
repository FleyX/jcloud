package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.enums.MediaFavoriteOwnerType;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;

import java.util.List;
import java.util.Map;

/**
 * 海报墙查询差异层策略（工单 08）：三型（电影/剧集/其他）各自提供分页查询、代表数据加载与逐行卡片装配，
 * 由 {@link MediaItemQueryDriver} 独占的分页装配管线统一驱动；分页过滤装配（keyword/genre/sort）、
 * 元数据/海报版本/文件名批量加载、收藏批量填充与 Page 重包装在驱动侧只有一份实现。
 *
 * @param <R> 分页行类型（MediaMovie / MediaSeries / MediaOther）
 * @param <F> 代表数据值类型（电影=明细行、剧集=集列表、其他=文件节点 ID）
 * @param <V> 卡片视图类型（MediaItemVo / MediaSeriesVo）
 */
public interface MediaItemQueryStrategy<R, F, V> {

    /**
     * 三型各自的分页查询（SQL/wrapper 不动）。user_id 硬过滤必须保留（跨库 search 依赖之）。
     */
    IPage<R> page(String userId, MediaPageQueryDto query);

    /**
     * 行 → 元数据 ID；无元数据行返回 null。
     */
    String metadataIdOf(R row);

    /**
     * 代表数据批量加载：以行 ID 为键（电影=明细行、剧集=集列表、其他=文件节点 ID）。
     */
    Map<String, F> representativeFiles(List<R> rows);

    /**
     * 代表数据涉及的文件节点 ID（驱动统一批量加载 fileNameMap）；无文件名的类型返回空列表。
     */
    List<String> fileNodeIdsOf(Map<String, F> representativeFiles);

    /**
     * 当前页行 ID 列表（收藏批量填充用，owner 为标题级行）。
     */
    List<String> ownerIdsOf(List<R> rows);

    /**
     * 逐行装配卡片（收藏态经 ctx.isFavorited 填充）。
     */
    V toCard(R row, MediaItemAssemblyContext<F> ctx);

    /**
     * 收藏归属类型。
     */
    MediaFavoriteOwnerType favoriteOwner();
}
