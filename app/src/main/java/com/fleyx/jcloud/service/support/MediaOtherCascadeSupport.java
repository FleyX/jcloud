package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaFavoriteOwnerType;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.service.MediaFavoriteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 其他库新模型级联删除支撑组件（ADR 0021 / issue #19）。
 * <p>
 * 票据 07 共享骨架重构后仅保留删除深度差异：单层「other 行」级联（连带其外部字幕记录，
 * t_media_subtitle.file_id 指向 other 行 ID）与收藏归属类型；锚文件来源内守卫、
 * 「选 ids→委派」骨架、未见行删除循环等公共部分委托 {@link MediaCascadeDriverSupport}。
 * 即时 reconcile 的删除、批次清理与媒体库删除共用同一套级联。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaOtherCascadeSupport {

    private final MediaOtherMapper mediaOtherMapper;
    private final MediaFavoriteService mediaFavoriteService;
    private final MediaCascadeDriverSupport mediaCascadeDriverSupport;

    /**
     * 级联删除若干 other 行：外部字幕记录 → other 行。
     *
     * @param otherIds other 行 ID 集合
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteOthersCascade(Collection<String> otherIds) {
        if (otherIds == null || otherIds.isEmpty()) {
            return;
        }
        mediaCascadeDriverSupport.deleteSubtitleByFileIds(otherIds);
        mediaOtherMapper.deleteBatchIds(otherIds);
        // 实体删除后清理其收藏记录（不限用户）
        mediaFavoriteService.deleteByOwners(MediaFavoriteOwnerType.OTHER, otherIds);
        log.info("级联删除其他条目 {} 条: ids={}", otherIds.size(), otherIds);
    }

    /**
     * 删除媒体库下指定来源目录的全部 other 行（媒体库增删来源目录时调用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDirectoryAndSourceIds(String directoryId, Collection<String> sourceIds) {
        mediaCascadeDriverSupport.deleteByDirectoryAndSourceIds(directoryId, sourceIds,
                (dirId, srcIds) -> mediaOtherMapper.selectList(new LambdaQueryWrapper<MediaOther>()
                                .eq(MediaOther::getDirectoryId, dirId)
                                .in(MediaOther::getSourceId, srcIds)
                                .select(MediaOther::getId))
                        .stream().map(MediaOther::getId).toList(),
                this::deleteOthersCascade);
    }

    /**
     * 删除媒体库下全部 other 行（媒体库删除时调用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDirectoryId(String directoryId) {
        mediaCascadeDriverSupport.deleteByDirectoryId(directoryId,
                dirId -> mediaOtherMapper.selectList(new LambdaQueryWrapper<MediaOther>()
                                .eq(MediaOther::getDirectoryId, dirId)
                                .select(MediaOther::getId))
                        .stream().map(MediaOther::getId).toList(),
                this::deleteOthersCascade);
    }

    /**
     * 即时删除亲眼确认消失的 other 行（视频文件被删除或移出本库来源）。
     * <p>
     * 防跨来源移动竞态：删除前确认锚文件节点已不存在或已不在本库任何来源下；
     * 文件仍存在本库来源内时跳过删除（留给新来源 reconcile 按锚改挂，进度不丢失）。
     *
     * @param sourceFullIdPaths 本库全部可达来源目录的完整物化路径
     */
    public void deleteUnseenOthers(List<MediaOther> existingRows, Set<String> seenRowIds,
                                   List<String> sourceFullIdPaths) {
        mediaCascadeDriverSupport.deleteUnseenRows(existingRows, seenRowIds, sourceFullIdPaths,
                MediaOther::getId, MediaOther::getFileNodeId,
                mediaOtherMapper::deleteBatchIds, MediaFavoriteOwnerType.OTHER, "即时删除消失的其他条目");
    }
}
