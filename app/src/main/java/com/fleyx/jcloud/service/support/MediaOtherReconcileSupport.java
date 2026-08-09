package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.service.support.MediaReconcileDriverSupport.RowReconciler;
import com.fleyx.jcloud.service.support.MediaScanDriverSupport.FileProbe;
import com.fleyx.jcloud.service.support.MediaScanDriverSupport.MediaScanContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 其他库单文件 reconcile 支撑组件（新模型，ADR 0021 / issue #19）。
 * <p>
 * 与 {@link MediaOtherScanSupport} 协作的两阶段编排：① {@link MediaOtherScanSupport#prepareSource}
 * （事务外）预计算文件哈希与 ffprobe 探测，{@link #upsertOther}（事务内）只做 DB 读写——other 行按
 * 视频文件节点锚定 upsert（跨来源移动时按锚改挂，进度保留）；② 同一来源全部文件 upsert 完成后，
 * {@link #deleteUnseenOthers}（事务内）删除亲眼确认消失的行。票据 07 共享骨架重构后，明细行
 * reconcile 循环委托 {@link MediaReconcileDriverSupport}，哈希命中的批次扫描时间刷新作为钩子保留。
 * ffprobe 探测移出事务边界，长耗时远程下载不再占用数据库连接（issue #17 F2 同款）；
 * 删除以 sourceFullIdPaths 守卫防跨来源移动竞态（issue #17 F1 同款）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaOtherReconcileSupport {

    private final MediaOtherMapper mediaOtherMapper;
    private final MediaOtherCascadeSupport mediaOtherCascadeSupport;
    private final MediaReconcileDriverSupport mediaReconcileDriverSupport;

    /** 单来源 prepare 结果：存量行、锚映射与探测结果，供事务内 upsert 直接使用。 */
    public record OtherPrepare(List<MediaOther> existingRows, Map<String, MediaOther> rowByNodeId,
                               Map<String, FileProbe> probeByFileId) {
    }

    /** 单来源 upsert 结果：本轮亲眼确认的 other 行 ID，供删除阶段使用。 */
    public record ReconcileResult(Set<String> seenRowIds) {
    }

    /**
     * 事务内单文件 upsert（探测已由 {@link MediaOtherScanSupport#prepareSource} 在事务外完成，
     * 此处只做 DB 读写）。other 行按视频文件节点锚定 upsert：锚命中且未变化则跳过；否则按锚
     * upsert 或改挂（跨来源移动的文件行直接改挂到本来源）。改名仅更新 name，播放进度保留。
     * 循环骨架委托 {@link MediaReconcileDriverSupport#reconcileRow}。
     */
    @Transactional(rollbackFor = Exception.class)
    public void upsertOther(MediaScanContext ctx, FileNode file, OtherPrepare prepare, Set<String> seenRowIds) {
        mediaReconcileDriverSupport.reconcileRow(ctx, file, prepare.probeByFileId(),
                prepare.rowByNodeId(), prepare.existingRows(), new RowReconciler<MediaOther>() {
                    @Override
                    public String fileHash(MediaOther row) {
                        return row.getFileHash();
                    }

                    @Override
                    public boolean hashHitExtraOk(MediaOther row, MediaScanContext c, FileProbe fp) {
                        return true;
                    }

                    @Override
                    public void hashHitExtraAction(MediaScanContext c, MediaOther row) {
                        // 哈希未变化：必须刷新批次扫描时间，否则批次清理会按旧 scan_time 误删
                        mediaOtherMapper.update(null, new LambdaUpdateWrapper<MediaOther>()
                                .eq(MediaOther::getId, row.getId())
                                .set(MediaOther::getScanTime, c.batchTime()));
                    }

                    @Override
                    public void markSeen(MediaOther row) {
                        seenRowIds.add(row.getId());
                    }

                    @Override
                    public MediaOther findByFileNodeId(String fileNodeId) {
                        return mediaOtherMapper.selectOne(new LambdaQueryWrapper<MediaOther>()
                                .eq(MediaOther::getFileNodeId, fileNodeId));
                    }

                    @Override
                    public MediaOther newRow(FileNode f) {
                        MediaOther row = new MediaOther();
                        row.setFileNodeId(f.getId());
                        return row;
                    }

                    @Override
                    public void assignAndPersist(MediaOther row, MediaScanContext c, FileNode f, FileProbe fp) {
                        if (row.getId() == null) {
                            row.setUserId(c.directory().getUserId());
                            row.setDirectoryId(c.directory().getId());
                            row.setSourceId(c.source().getId());
                            row.setName(f.getName());
                            row.setProgressMs(0L);
                            row.setScanTime(c.batchTime());
                            row.setFileHash(fp.fileHash());
                        } else {
                            row.setDirectoryId(c.directory().getId());
                            row.setSourceId(c.source().getId());
                            row.setName(f.getName());
                            row.setFileHash(fp.fileHash());
                            row.setScanTime(c.batchTime());
                        }
                        if (fp.probe() != null) {
                            fillProbe(row, fp.probe());
                        }
                        if (row.getId() == null) {
                            mediaOtherMapper.insert(row);
                        } else {
                            mediaOtherMapper.updateById(row);
                        }
                    }
                });
    }

    private void fillProbe(MediaOther row, MediaProbeResult probe) {
        row.setDurationMs(probe.durationMs());
        row.setContainer(probe.container());
        row.setVideoCodec(probe.videoCodec());
        row.setAudioCodec(probe.audioCodec());
        row.setWidth(probe.width());
        row.setHeight(probe.height());
    }

    /**
     * 事务内单来源删除阶段：删除亲眼确认消失的 other 行（连带其外部字幕记录）。
     * 仅在本来源全部文件 upsert 成功（来源未部分失败）后调用（issue #17 F1 同款）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteUnseenOthers(MediaScanContext ctx, OtherPrepare prepare, ReconcileResult result) {
        mediaOtherCascadeSupport.deleteUnseenOthers(prepare.existingRows(), result.seenRowIds(),
                ctx.sourceFullIdPaths());
    }
}
