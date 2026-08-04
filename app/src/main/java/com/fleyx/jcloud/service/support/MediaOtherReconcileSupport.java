package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaOther;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 其他库单文件 reconcile 支撑组件（新模型，ADR 0021 / issue #19）。
 * <p>
 * 与 {@link MediaOtherScanSupport} 协作的两阶段编排：① {@link MediaOtherScanSupport#prepareSource}
 * （事务外）预计算文件哈希与 ffprobe 探测，{@link #upsertOther}（事务内）只做 DB 读写——other 行按
 * 视频文件节点锚定 upsert（跨来源移动时按锚改挂，进度保留）；② 同一来源全部文件 upsert 完成后，
 * {@link #deleteUnseenOthers}（事务内）删除亲眼确认消失的行。ffprobe 探测移出事务边界，
 * 长耗时远程下载不再占用数据库连接（issue #17 F2 同款）；删除以 sourceFullIdPaths 守卫防跨来源
 * 移动竞态（issue #17 F1 同款）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaOtherReconcileSupport {

    private final MediaOtherMapper mediaOtherMapper;
    private final MediaOtherCascadeSupport mediaOtherCascadeSupport;
    private final MediaScanSupport mediaScanSupport;

    /**
     * 一轮来源扫描共享上下文（收束 username/idToName/sourceFullIdPath 等整簇透传参数，issue #17 F4 同款）。
     *
     * @param sourceFullIdPaths 本库全部可达来源目录的完整物化路径（即时删除的跨来源移动守卫）
     */
    public record OtherScanContext(MediaDirectory directory, MediaDirectorySource source, boolean force,
                                   LocalDateTime batchTime, String username, Map<String, String> idToName,
                                   String sourceFullIdPath, List<String> sourceFullIdPaths) {
    }

    /** 单文件探测结果（事务外预计算）。探测失败为 null，仅记日志不中断扫描。 */
    public record FileProbe(String fileHash, MediaProbeResult probe) {
    }

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
     */
    @Transactional(rollbackFor = Exception.class)
    public void upsertOther(OtherScanContext ctx, FileNode file, OtherPrepare prepare, Set<String> seenRowIds) {
        FileProbe fp = prepare.probeByFileId().get(file.getId());
        if (fp == null) {
            log.debug("文件未在预计算中命中，跳过: {}", file.getName());
            return;
        }
        MediaOther row = prepare.rowByNodeId().get(file.getId());
        if (row != null && !ctx.force() && Objects.equals(row.getFileHash(), fp.fileHash())) {
            // 哈希未变化：跳过探测与 upsert，但必须刷新批次扫描时间，否则批次清理会按旧 scan_time 误删
            mediaOtherMapper.update(null, new LambdaUpdateWrapper<MediaOther>()
                    .eq(MediaOther::getId, row.getId())
                    .set(MediaOther::getScanTime, ctx.batchTime()));
            seenRowIds.add(row.getId());
            return;
        }
        if (row == null) {
            // 本来源未命中时按锚全局查找：跨来源/跨库移动的文件行直接改挂
            row = mediaOtherMapper.selectOne(new LambdaQueryWrapper<MediaOther>()
                    .eq(MediaOther::getFileNodeId, file.getId()));
            if (row != null) {
                prepare.existingRows().add(row);
                prepare.rowByNodeId().put(file.getId(), row);
            }
        }
        boolean isNew = row == null;
        if (isNew) {
            row = new MediaOther();
            row.setUserId(ctx.directory().getUserId());
            row.setDirectoryId(ctx.directory().getId());
            row.setSourceId(ctx.source().getId());
            row.setFileNodeId(file.getId());
            row.setName(file.getName());
            row.setProgressMs(0L);
            row.setScanTime(ctx.batchTime());
            row.setFileHash(fp.fileHash());
            if (fp.probe() != null) {
                fillProbe(row, fp.probe());
            }
            mediaOtherMapper.insert(row);
            prepare.existingRows().add(row);
            prepare.rowByNodeId().put(file.getId(), row);
            seenRowIds.add(row.getId());
            return;
        }
        row.setDirectoryId(ctx.directory().getId());
        row.setSourceId(ctx.source().getId());
        row.setName(file.getName());
        row.setFileHash(fp.fileHash());
        row.setScanTime(ctx.batchTime());
        if (fp.probe() != null) {
            fillProbe(row, fp.probe());
        }
        mediaOtherMapper.updateById(row);
        seenRowIds.add(row.getId());
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
    public void deleteUnseenOthers(OtherScanContext ctx, OtherPrepare prepare, ReconcileResult result) {
        mediaOtherCascadeSupport.deleteUnseenOthers(prepare.existingRows(), result.seenRowIds(),
                ctx.sourceFullIdPaths());
    }
}
