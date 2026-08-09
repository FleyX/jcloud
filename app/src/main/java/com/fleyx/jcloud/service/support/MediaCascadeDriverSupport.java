package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaFavoriteOwnerType;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.service.MediaFavoriteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 三型级联删除共享骨架驱动器（电视/电影/其他，ADR 0021 / issue #17/18/19）。
 * <p>
 * 三型 Cascade 类只保留删除深度（电视四层集文件→集→季→剧、电影两层、其他单层）与收藏归属等差异，
 * 公共部分收束于此：锚文件来源内守卫、实体元数据行删除、外部字幕记录删除、媒体库删除的
 * 「选 ids→委派」骨架与即时 reconcile 的未见行删除循环（skip 守卫：id 命中 seen / 锚文件仍在
 * 来源内→跳过；否则字幕清理 → 批量删明细行 → 收藏清理 → 日志，先字幕行后明细行的顺序不变）。
 * 本类不开启事务：删除均在调用方事务内执行（各型级联删除方法自身带 @Transactional）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaCascadeDriverSupport {

    private final FileMapper fileMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final MediaSubtitleSupport mediaSubtitleSupport;
    private final MediaFavoriteService mediaFavoriteService;

    /**
     * 锚文件节点仍存在且仍位于本库任一来源目录子树内。
     */
    private boolean anchoredNodeInSources(String fileNodeId, List<String> sourceFullIdPaths) {
        FileNode node = fileMapper.selectById(fileNodeId);
        if (node == null || node.getPath() == null) {
            return false;
        }
        for (String sourcePath : sourceFullIdPaths) {
            if (node.getPath().startsWith(sourcePath + ".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 删除实体一对一绑定的元数据行（owner 反向指针定位），无对应行时无事发生。
     *
     * @param ownerType 归属实体类型（{@link com.fleyx.jcloud.common.enums.MediaMetadataOwnerType}）
     * @param ownerId   归属实体 ID
     */
    public void deleteMetadata(String ownerType, String ownerId) {
        mediaMetadataMapper.delete(new LambdaQueryWrapper<MediaMetadata>()
                .eq(MediaMetadata::getOwnerType, ownerType)
                .eq(MediaMetadata::getOwnerId, ownerId));
    }

    /**
     * 删除文件明细行的外部字幕记录（明细行被清理时一并调用）。
     *
     * @param fileRowIds 文件明细行 ID 集合（集文件/电影文件明细行或 other 行）
     */
    public void deleteSubtitleByFileIds(Collection<String> fileRowIds) {
        mediaSubtitleSupport.deleteByFileIds(fileRowIds);
    }

    /**
     * 「选 ids→委派」骨架：媒体库下按来源目录选行 ID 并委派级联删除（调用方事务内执行）。
     * 来源目录列表为空时不触碰任何数据。
     */
    public void deleteByDirectoryAndSourceIds(String directoryId, Collection<String> sourceIds,
                                              BiFunction<String, Collection<String>, List<String>> idSelector,
                                              Consumer<List<String>> cascadeDeleter) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return;
        }
        cascadeDeleter.accept(idSelector.apply(directoryId, sourceIds));
    }

    /**
     * 「选 ids→委派」骨架：媒体库下全量选行 ID 并委派级联删除（调用方事务内执行）。
     */
    public void deleteByDirectoryId(String directoryId, Function<String, List<String>> idSelector,
                                    Consumer<List<String>> cascadeDeleter) {
        cascadeDeleter.accept(idSelector.apply(directoryId));
    }

    /**
     * 通用未见行删除循环：id 命中 seen 或锚文件仍在本库来源内→跳过（留给新父级/新来源改挂）；
     * 否则字幕清理 → 批量删明细行 → 收藏清理 → 日志。删除顺序「先字幕行后明细行」与现有实现一致。
     *
     * @param existingRows       本轮 prepare 时加载的存量明细行（含 upsert 内改挂认领/新建追加的行）
     * @param seenRowIds         本轮 upsert 亲眼确认的明细行 ID
     * @param sourceFullIdPaths  本库全部可达来源目录的完整物化路径
     * @param favoriteOwnerType  明细行可收藏时的归属类型；明细行不可收藏（集文件/电影文件）传 null
     * @param logLabel           删除日志前缀；不需要日志时传 null
     */
    public <T> void deleteUnseenRows(Collection<T> existingRows, Set<String> seenRowIds,
                                     List<String> sourceFullIdPaths,
                                     Function<T, String> idGetter, Function<T, String> fileNodeIdGetter,
                                     Consumer<List<String>> batchDeleter,
                                     MediaFavoriteOwnerType favoriteOwnerType, String logLabel) {
        List<String> removedIds = new ArrayList<>();
        for (T row : existingRows) {
            String id = idGetter.apply(row);
            if (id == null || seenRowIds.contains(id)) {
                continue;
            }
            if (anchoredNodeInSources(fileNodeIdGetter.apply(row), sourceFullIdPaths)) {
                log.debug("锚文件已移至本库其他位置，行留给新父级/新来源改挂: {}", fileNodeIdGetter.apply(row));
                continue;
            }
            removedIds.add(id);
        }
        if (removedIds.isEmpty()) {
            return;
        }
        mediaSubtitleSupport.deleteByFileIds(removedIds);
        batchDeleter.accept(removedIds);
        if (favoriteOwnerType != null) {
            mediaFavoriteService.deleteByOwners(favoriteOwnerType, removedIds);
        }
        if (logLabel != null) {
            log.info("{} {} 条: ids={}", logLabel, removedIds.size(), removedIds);
        }
    }
}
