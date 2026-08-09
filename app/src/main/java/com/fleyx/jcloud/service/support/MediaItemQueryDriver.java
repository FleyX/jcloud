package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.service.MediaFavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 海报墙分页装配管线驱动（工单 08）：独占
 * 「page(rows) → metadataMap → nodeVersionMap → representativeFiles → fileNameMap → 逐行 toCard →
 * favorites 批量填充 → Page&lt;VO&gt; 重包装(current,size,total)」管线，
 * 三型查询只以 {@link MediaItemQueryStrategy} 薄差异层注入；查询返回 VO 与分页/排序行为不变（02 表征测试锁定）。
 */
@Component
@RequiredArgsConstructor
public class MediaItemQueryDriver {

    private final MediaItemVoSupport mediaItemVoSupport;
    private final MediaFavoriteService mediaFavoriteService;

    /**
     * 驱动一次海报墙分页装配。
     *
     * @param strategy 类型差异层（分页查询/代表数据/卡片装配/收藏 ownerType）
     * @param userId   当前用户
     * @param query    分页过滤排序入参
     * @return 装配完成的分页视图（records 为卡片，分页参数与原始查询一致）
     */
    public <R, F, V> IPage<V> run(MediaItemQueryStrategy<R, F, V> strategy, String userId, MediaPageQueryDto query) {
        IPage<R> result = strategy.page(userId, query);
        List<R> rows = result.getRecords();
        Map<String, MediaMetadata> metadataMap = mediaItemVoSupport.loadMetadataMap(
                rows.stream().map(strategy::metadataIdOf).toList());
        Map<String, Long> nodeVersionMap = mediaItemVoSupport.loadNodeVersionMap(
                metadataMap.values().stream().map(MediaMetadata::getPosterFileNodeId).toList());
        Map<String, F> representativeFiles = strategy.representativeFiles(rows);
        Map<String, String> fileNameMap = mediaItemVoSupport.loadFileNameMap(
                strategy.fileNodeIdsOf(representativeFiles));
        Set<String> favoritedIds = mediaFavoriteService.listFavoritedOwnerIds(userId, strategy.favoriteOwner(),
                strategy.ownerIdsOf(rows));
        MediaItemAssemblyContext<F> ctx = new MediaItemAssemblyContext<>(
                metadataMap, nodeVersionMap, fileNameMap, representativeFiles, favoritedIds);
        List<V> vos = rows.stream().map(row -> strategy.toCard(row, ctx)).toList();
        Page<V> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(vos);
        return voPage;
    }
}
