package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaScanOutcome;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.service.support.MediaOtherReconcileSupport.FileProbe;
import com.fleyx.jcloud.service.support.MediaOtherReconcileSupport.OtherPrepare;
import com.fleyx.jcloud.service.support.MediaOtherReconcileSupport.OtherScanContext;
import com.fleyx.jcloud.service.support.MediaOtherReconcileSupport.ReconcileResult;
import com.fleyx.jcloud.util.FilePathUtil;
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
 * 其他媒体库新模型扫描编排支撑组件（ADR 0021 / issue #19）。
 * <p>
 * 文件级扫描：来源目录子树内每个视频文件一行落 t_media_other（锚 = 视频文件 file_node_id），
 * 任意层级目录均可，不做结构/名称识别、不刮元数据；缩略图沿用现有文件预览机制
 * （网格按 fileNodeId 走 /files/{id}/preview?type=poster 按需生成，行为与现状一致）。
 * <p>
 * 复用同一套两阶段 reconcile 与批次清理管线语义（与电影/电视库一致）：阶段一事务外探测 +
 * 事务内锚定 upsert 先行，来源全部文件 upsert 完成后阶段二按文件删除亲眼确认消失的行；
 * 来源部分失败（含掉线）时阶段二整体不做删除。扫描末尾按批次扫描时间执行三道闸批次清理：
 * ① 仅完整成功（无部分失败）的扫描执行；② 仅本轮「可达且完整扫完」的来源目录参与结算；
 * ③ 仅删除有资格来源下 scan_time 早于批次时间的 other 行。清理按 directory_id 库内闭环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaOtherScanSupport {

    private final FileMapper fileMapper;
    private final MediaOtherMapper mediaOtherMapper;
    private final MediaScanSupport mediaScanSupport;
    private final MediaOtherReconcileSupport mediaOtherReconcileSupport;
    private final MediaOtherCascadeSupport mediaOtherCascadeSupport;
    private final MediaSubtitleSupport mediaSubtitleSupport;
    private final MediaTaskSupport mediaTaskSupport;

    private enum SourceOutcome {
        OK, PARTIAL, CANCELLED
    }

    /**
     * 其他库整轮扫描：批次时间取扫描开始时刻，逐来源扫描并在完整成功时执行批次清理。
     *
     * @param directory 媒体库
     * @param sources   来源目录列表
     * @param force     是否强制全量
     * @param username  用户名（物理路径推导）
     * @return 扫描结果
     */
    public MediaScanOutcome scanDirectory(MediaDirectory directory, List<MediaDirectorySource> sources,
                                          boolean force, String username) {
        LocalDateTime batchTime = LocalDateTime.now();
        // 本库全部可达来源目录的完整物化路径：即时删除的跨来源移动守卫（文件仍在库内来源下则不删，留给新来源改挂）
        List<String> sourceFullIdPaths = sources.stream()
                .map(s -> fileMapper.selectById(s.getFileNodeId()))
                .filter(f -> f != null && directory.getUserId().equals(f.getUserId()) && "folder".equals(f.getType()))
                .map(FilePathUtil::fullIdPath)
                .toList();
        Set<String> qualifiedSourceIds = new HashSet<>();
        boolean partial = false;
        for (MediaDirectorySource source : sources) {
            if (mediaTaskSupport.isCancelled(directory.getId())) {
                log.info("媒体库扫描被中断: {}", directory.getId());
                return MediaScanOutcome.CANCELLED;
            }
            SourceOutcome outcome = scanSource(directory, source, force, batchTime, username, sourceFullIdPaths);
            if (outcome == SourceOutcome.CANCELLED) {
                log.info("媒体库扫描被中断: {}", directory.getId());
                return MediaScanOutcome.CANCELLED;
            }
            if (outcome == SourceOutcome.PARTIAL) {
                partial = true;
            } else {
                qualifiedSourceIds.add(source.getId());
            }
        }
        // 三道闸：① 仅完整成功的扫描执行批次清理
        if (!partial) {
            batchCleanup(directory, qualifiedSourceIds, batchTime);
            return MediaScanOutcome.COMPLETED;
        }
        return MediaScanOutcome.PARTIAL;
    }

    /**
     * 批次清理：仅删除本轮完整扫完的来源目录下（闸②）、scan_time 早于批次时间的 other 行（闸③）。
     */
    private void batchCleanup(MediaDirectory directory, Set<String> qualifiedSourceIds, LocalDateTime batchTime) {
        if (qualifiedSourceIds.isEmpty()) {
            return;
        }
        List<String> staleIds = mediaOtherMapper.selectList(new LambdaQueryWrapper<MediaOther>()
                        .eq(MediaOther::getDirectoryId, directory.getId())
                        .in(MediaOther::getSourceId, qualifiedSourceIds)
                        .and(w -> w.isNull(MediaOther::getScanTime)
                                .or().lt(MediaOther::getScanTime, batchTime))
                        .select(MediaOther::getId))
                .stream().map(MediaOther::getId).toList();
        if (!staleIds.isEmpty()) {
            log.info("批次清理删除消失的其他条目: directory={}, count={}", directory.getId(), staleIds.size());
            mediaOtherCascadeSupport.deleteOthersCascade(staleIds);
        }
    }

    /**
     * 扫描单个来源目录：收集子树内全部视频文件，两阶段 reconcile。
     * 来源目录不可达时不触碰其数据，记为部分失败（不参与批次清理结算，也不做即时删除）。
     */
    private SourceOutcome scanSource(MediaDirectory directory, MediaDirectorySource source,
                                     boolean force, LocalDateTime batchTime, String username,
                                     List<String> sourceFullIdPaths) {
        String userId = directory.getUserId();
        FileNode folder = fileMapper.selectById(source.getFileNodeId());
        if (folder == null || !userId.equals(folder.getUserId()) || !"folder".equals(folder.getType())) {
            log.warn("来源目录不可达，本轮不结算其数据: sourceId={}", source.getId());
            return SourceOutcome.PARTIAL;
        }
        List<FileNode> nodes = fileMapper.selectByIdPathPrefix(userId, folder.getPath(), folder.getId());
        Map<String, String> idToName = FilePathUtil.buildNameCache(nodes);
        mediaScanSupport.fillAncestorNames(folder, userId, idToName);
        String folderFullIdPath = FilePathUtil.fullIdPath(folder);
        OtherScanContext ctx = new OtherScanContext(directory, source, force, batchTime, username,
                idToName, folderFullIdPath, sourceFullIdPaths);

        List<FileNode> videoFiles = nodes.stream()
                .filter(n -> "file".equals(n.getType()) && MediaFileNameParser.isVideoFile(n.getName()))
                .toList();
        // 阶段一：全部文件的「事务外探测 + 事务内锚定 upsert」先行；跨来源移动的行由目标来源按锚认领
        OtherPrepare prepare = prepareSource(ctx, videoFiles);
        ReconcileResult reconciled = new ReconcileResult(new HashSet<>());
        boolean partial = false;
        for (FileNode file : videoFiles) {
            if (mediaTaskSupport.isCancelled(directory.getId())) {
                return SourceOutcome.CANCELLED;
            }
            try {
                mediaOtherReconcileSupport.upsertOther(ctx, file, prepare, reconciled.seenRowIds());
            } catch (Exception e) {
                log.warn("其他条目 upsert 失败: {}", file.getName(), e);
                partial = true;
            }
        }
        if (partial) {
            // 来源部分失败：阶段二整体不做删除（已提交的 upsert/改挂保留，下轮完整扫描自愈）
            return SourceOutcome.PARTIAL;
        }
        if (mediaTaskSupport.isCancelled(directory.getId())) {
            return SourceOutcome.CANCELLED;
        }
        // 阶段二：全部文件 upsert 完成后，删除亲眼确认消失的 other 行（跨来源移动的已被目标来源认领）
        try {
            mediaOtherReconcileSupport.deleteUnseenOthers(ctx, prepare, reconciled);
        } catch (Exception e) {
            log.warn("其他条目即时删除失败: sourceId={}", source.getId(), e);
            partial = true;
        }
        if (partial) {
            return SourceOutcome.PARTIAL;
        }
        // 外部字幕关联重建（挂到 other 行，issue #19）
        rebuildSubtitles(ctx, nodes);
        return SourceOutcome.OK;
    }

    /**
     * 事务外预计算（issue #17 F2 同款）：按锚读存量行，对「新文件或哈希变化或强制全量」的文件执行
     * ffprobe 探测。探测可能全量下载远程文件，必须在进入 {@link MediaOtherReconcileSupport#upsertOther}
     * 事务前完成，避免长占数据库连接；哈希未变的文件跳过探测，事务内同样按哈希命中跳过。
     */
    private OtherPrepare prepareSource(OtherScanContext ctx, List<FileNode> videoFiles) {
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
                    probeNeeded ? probeQuietly(file, ctx.username(), ctx.idToName()) : null));
        }
        return new OtherPrepare(existingRows, rowByNodeId, probeByFileId);
    }

    private MediaProbeResult probeQuietly(FileNode file, String username, Map<String, String> idToName) {
        try {
            return mediaScanSupport.probeFile(file, username, idToName);
        } catch (Exception e) {
            log.warn("ffprobe 探测失败: {}, {}", file.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * 重建来源目录下 other 行的外部字幕关联（阶段二删除完成后执行，file_id 指向 other 行 ID）。
     */
    private void rebuildSubtitles(OtherScanContext ctx, List<FileNode> nodes) {
        List<MediaOther> rows = mediaOtherMapper.selectList(new LambdaQueryWrapper<MediaOther>()
                .eq(MediaOther::getDirectoryId, ctx.directory().getId())
                .eq(MediaOther::getSourceId, ctx.source().getId()));
        List<MediaSubtitleSupport.FileRef> refs = rows.stream()
                .map(r -> new MediaSubtitleSupport.FileRef(r.getId(), r.getFileNodeId()))
                .toList();
        mediaSubtitleSupport.rebuildForSource(refs, nodes);
    }
}
