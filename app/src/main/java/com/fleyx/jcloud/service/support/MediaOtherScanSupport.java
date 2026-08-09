package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.service.support.MediaScanDriverSupport.FileProbe;
import com.fleyx.jcloud.service.support.MediaScanDriverSupport.MediaScanContext;
import com.fleyx.jcloud.service.support.MediaScanDriverSupport.ScanStrategy;
import com.fleyx.jcloud.service.support.MediaOtherReconcileSupport.OtherPrepare;
import com.fleyx.jcloud.service.support.MediaOtherReconcileSupport.ReconcileResult;
import com.fleyx.jcloud.service.support.MediaSubtitleSupport.FileRef;
import com.fleyx.jcloud.util.MediaFileNameParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 其他媒体库新模型扫描策略（ADR 0021 / issue #19）。
 * <p>
 * 文件级扫描：来源目录子树内每个视频文件一行落 t_media_other（锚 = 视频文件 file_node_id），
 * 任意层级目录均可，不做结构/名称识别、不刮元数据。票据 07 共享骨架重构后仅做差异装配：
 * 「每来源单实体 + 共享 seenRowIds 累加器」归一为逐实体模型的单实体特例（anchor 可为 null），
 * 公共编排由 {@link MediaScanDriverSupport} 独占；两阶段语义不变（issue #17 F1 同款）。
 * 缩略图沿用现有文件预览机制（网格按 fileNodeId 走 /files/{id}/preview?type=poster 按需生成）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaOtherScanSupport implements ScanStrategy<MediaOtherScanSupport.OtherScanEntity,
        OtherPrepare, ReconcileResult> {

    private final MediaOtherMapper mediaOtherMapper;
    private final MediaScanSupport mediaScanSupport;
    private final MediaScanDriverSupport mediaScanDriverSupport;
    private final MediaOtherReconcileSupport mediaOtherReconcileSupport;
    private final MediaOtherCascadeSupport mediaOtherCascadeSupport;
    private final MediaTaskSupport mediaTaskSupport;

    /** 每来源单实体（anchor 可为 null 的单实体特例）：来源 ID（诊断日志用）+ 来源子树内全部视频文件。 */
    public record OtherScanEntity(String sourceId, List<FileNode> videoFiles) {
    }

    @Override
    public List<OtherScanEntity> group(MediaScanContext ctx, List<FileNode> nodes) {
        List<FileNode> videoFiles = nodes.stream()
                .filter(n -> "file".equals(n.getType()) && MediaFileNameParser.isVideoFile(n.getName()))
                .toList();
        return List.of(new OtherScanEntity(ctx.source().getId(), videoFiles));
    }

    @Override
    public OtherPrepare prepare(MediaScanContext ctx, OtherScanEntity entity) {
        return prepareSource(ctx, entity.videoFiles());
    }

    @Override
    public ReconcileResult upsert(MediaScanContext ctx, OtherScanEntity entity, OtherPrepare prepare) {
        // 每来源单实体：逐文件 upsert 累加共享 seenRowIds（跨来源移动的行由目标来源按锚认领）；
        // 单文件失败跳过并继续后续文件（与原实现一致），全部尝试完毕后以异常上报驱动器记为来源部分失败
        ReconcileResult result = new ReconcileResult(new HashSet<>());
        boolean partial = false;
        for (FileNode file : entity.videoFiles()) {
            if (mediaTaskSupport.isCancelled(ctx.directory().getId())) {
                throw new MediaScanDriverSupport.ScanCancelledException();
            }
            try {
                mediaOtherReconcileSupport.upsertOther(ctx, file, prepare, result.seenRowIds());
            } catch (Exception e) {
                log.warn("其他条目 upsert 失败: {}", file.getName(), e);
                partial = true;
            }
        }
        if (partial) {
            throw new IllegalStateException("来源部分文件 upsert 失败: sourceId=" + entity.sourceId());
        }
        return result;
    }

    @Override
    public void deleteUnseen(MediaScanContext ctx, OtherScanEntity entity, OtherPrepare prepare,
                             ReconcileResult result) {
        mediaOtherReconcileSupport.deleteUnseenOthers(ctx, prepare, result);
    }

    @Override
    public String entityLabel(OtherScanEntity entity) {
        return "sourceId=" + entity.sourceId();
    }

    @Override
    public List<FileRef> subtitleRefs(MediaScanContext ctx) {
        List<MediaOther> rows = mediaOtherMapper.selectList(new LambdaQueryWrapper<MediaOther>()
                .eq(MediaOther::getDirectoryId, ctx.directory().getId())
                .eq(MediaOther::getSourceId, ctx.source().getId()));
        return rows.stream()
                .map(r -> new FileRef(r.getId(), r.getFileNodeId()))
                .toList();
    }

    @Override
    public List<String> staleIds(String directoryId, Set<String> qualifiedSourceIds, LocalDateTime batchTime) {
        return mediaOtherMapper.selectList(new LambdaQueryWrapper<MediaOther>()
                        .eq(MediaOther::getDirectoryId, directoryId)
                        .in(MediaOther::getSourceId, qualifiedSourceIds)
                        .and(w -> w.isNull(MediaOther::getScanTime)
                                .or().lt(MediaOther::getScanTime, batchTime))
                        .select(MediaOther::getId))
                .stream().map(MediaOther::getId).toList();
    }

    @Override
    public void deleteStale(List<String> ids) {
        if (!ids.isEmpty()) {
            log.info("批次清理删除消失的其他条目: count={}", ids.size());
            mediaOtherCascadeSupport.deleteOthersCascade(ids);
        }
    }

    @Override
    public void afterBatchCleanup(String directoryId) {
        // 其他库无实体级元数据，完整性重算为空实现
    }

    /**
     * 事务外预计算（issue #17 F2 同款）：按锚读存量行，对「新文件或哈希变化或强制全量」的文件执行
     * ffprobe 探测。探测可能全量下载远程文件，必须在进入 {@link MediaOtherReconcileSupport#upsertOther}
     * 事务前完成，避免长占数据库连接；哈希未变的文件跳过探测，事务内同样按哈希命中跳过。
     */
    private OtherPrepare prepareSource(MediaScanContext ctx, List<FileNode> videoFiles) {
        List<MediaOther> existingRows = mediaOtherMapper.selectList(new LambdaQueryWrapper<MediaOther>()
                .eq(MediaOther::getDirectoryId, ctx.directory().getId())
                .eq(MediaOther::getSourceId, ctx.source().getId()));
        Map<String, MediaOther> rowByNodeId = existingRows.stream()
                .collect(Collectors.toMap(MediaOther::getFileNodeId, Function.identity()));
        Map<String, FileProbe> probeByFileId = new HashMap<>();
        for (FileNode file : videoFiles) {
            String fileHash = mediaScanSupport.computeFileHash(file, ctx.source().getId(),
                    ctx.sourceFullIdPath(), ctx.idToName());
            MediaOther row = rowByNodeId.get(file.getId());
            boolean probeNeeded = row == null || ctx.force() || !Objects.equals(row.getFileHash(), fileHash);
            probeByFileId.put(file.getId(), new FileProbe(fileHash,
                    probeNeeded ? mediaScanDriverSupport.probeQuietly(file, ctx.username(), ctx.idToName())
                            : null));
        }
        return new OtherPrepare(existingRows, rowByNodeId, probeByFileId);
    }
}
