package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.MediaScanOutcome;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 三型扫描共享骨架驱动器（电视/电影/其他，ADR 0021 / issue #17/18/19）。
 * <p>
 * 票据 07 共享骨架重构后独占扫描编排公共流程：scanDirectory/scanSource 双循环、isCancelled
 * 检查、sourceFullIdPaths 计算、SourceOutcome/partial/qualifiedSourceIds 聚合、批次清理三道闸
 * 门控（闸①仅无 partial 执行、闸②按来源资格、闸③ scan_time 早于批次时间）、probeQuietly 与
 * 字幕重建驱动；三型差异通过 {@link ScanStrategy} 策略注入。共享扫描上下文与单文件探测结果
 * 亦收束于此。扫描行为与重构前完全一致（由 01 的表征测试锁定）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaScanDriverSupport {

    private final FileMapper fileMapper;
    private final MediaScanSupport mediaScanSupport;
    private final MediaTaskSupport mediaTaskSupport;
    private final MediaSubtitleSupport mediaSubtitleSupport;

    /**
     * 一轮来源扫描共享上下文（收束 username/idToName/sourceFullIdPath 等整簇透传参数，issue #17 F4）。
     *
     * @param sourceFullIdPaths 本库全部可达来源目录的完整物化路径（即时删除的跨实体移动守卫）
     */
    public record MediaScanContext(MediaDirectory directory, MediaDirectorySource source, boolean force,
                                   LocalDateTime batchTime, String username, Map<String, String> idToName,
                                   String sourceFullIdPath, List<String> sourceFullIdPaths) {
    }

    /** 单文件探测结果（事务外预计算）。探测失败为 null，仅记日志不中断扫描。 */
    public record FileProbe(String fileHash, MediaProbeResult probe) {
    }

    private enum SourceOutcome {
        OK, PARTIAL, CANCELLED
    }

    /**
     * 策略在实体内部检测到协作式取消时抛出（如其他库逐文件 upsert 循环内的逐文件取消检查），
     * 驱动器据此将本来源记为 CANCELLED 而非 PARTIAL。
     */
    public static class ScanCancelledException extends RuntimeException {
    }

    /**
     * 三型扫描策略（各型 ScanSupport 实现，只做差异装配）：
     * group 分组、prepare 事务外探测、upsert 事务内锚定 reconcile、deleteUnseen 阶段二即时删除、
     * subtitleRefs 字幕关联重建、staleIds/deleteStale 批次清理、afterBatchCleanup 完整性批量重算。
     *
     * @param <E> 实体类型（电视剧/电影文件夹/其他来源单实体）
     * @param <P> prepare 结果类型
     * @param <R> upsert 结果类型
     */
    public interface ScanStrategy<E, P, R> {

        /**
         * 分组：按来源子树节点生成待 reconcile 的实体列表（电视三层剧分组、电影文件夹锚定、
         * 其他每来源单实体）。
         */
        List<E> group(MediaScanContext ctx, List<FileNode> nodes);

        /**
         * 事务外预计算（issue #17 F2）：按锚读存量行，对「新文件或哈希变化或强制全量」的文件执行
         * ffprobe 探测。探测可能全量下载远程文件，必须在进入 {@link #upsert} 事务前完成。
         */
        P prepare(MediaScanContext ctx, E entity);

        /**
         * 事务内锚定 upsert/改挂（委托对应 Reconcile），逐实体各自提交。
         */
        R upsert(MediaScanContext ctx, E entity, P prepare);

        /**
         * 阶段二即时删除（全部实体 upsert 完成后执行，跨实体移动的子项已被目标按锚认领）。
         */
        void deleteUnseen(MediaScanContext ctx, E entity, P prepare, R result);

        /**
         * 实体展示名（诊断日志用）。
         */
        String entityLabel(E entity);

        /**
         * 来源目录下当前文件明细行的外部字幕引用（挂到明细行/other 行，issue #19）。
         */
        List<MediaSubtitleSupport.FileRef> subtitleRefs(MediaScanContext ctx);

        /**
         * 批次清理选消失行（闸③：scan_time 早于批次时间；闸②：仅有资格来源参与由入参保证）。
         */
        List<String> staleIds(String directoryId, Set<String> qualifiedSourceIds, LocalDateTime batchTime);

        /**
         * 批次清理删除选中行（级联删除）。
         */
        void deleteStale(List<String> ids);

        /**
         * 批次清理后的完整性批量重算（电视/电影按库重算；其他空实现）。级联删除连带删元数据行，
         * 必须在清理之后执行。
         */
        void afterBatchCleanup(String directoryId);
    }

    /**
     * 媒体库整轮扫描：批次时间取扫描开始时刻，逐来源扫描并在完整成功时执行批次清理。
     *
     * @param directory 媒体库
     * @param sources   来源目录列表
     * @param force     是否强制全量
     * @param username  用户名（物理路径推导）
     * @param strategy  三型扫描策略
     * @return 扫描结果
     */
    public <E, P, R> MediaScanOutcome scanDirectory(MediaDirectory directory, List<MediaDirectorySource> sources,
                                                    boolean force, String username, ScanStrategy<E, P, R> strategy) {
        LocalDateTime batchTime = LocalDateTime.now();
        // 本库全部可达来源目录的完整物化路径：即时删除的跨实体移动守卫（文件仍在库内来源下则不删，留给新父级改挂）
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
            SourceOutcome outcome = scanSource(directory, source, force, batchTime, username,
                    sourceFullIdPaths, strategy);
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
        // 三道闸：① 仅完整成功的扫描执行批次清理（闸②按来源资格、闸③ scan_time 早于批次时间在策略查询内）；
        // 清理后对本库条目重算完整性（级联删除连带删元数据行，必须在清理之后）
        if (!partial) {
            if (!qualifiedSourceIds.isEmpty()) {
                strategy.deleteStale(strategy.staleIds(directory.getId(), qualifiedSourceIds, batchTime));
            }
            strategy.afterBatchCleanup(directory.getId());
            return MediaScanOutcome.COMPLETED;
        }
        return MediaScanOutcome.PARTIAL;
    }

    /**
     * 扫描单个来源目录：加载子树、按策略分组，两阶段 reconcile。
     * 来源目录不可达时不触碰其数据，记为部分失败（不参与批次清理结算，也不做即时删除）。
     */
    private <E, P, R> SourceOutcome scanSource(MediaDirectory directory, MediaDirectorySource source,
                                               boolean force, LocalDateTime batchTime, String username,
                                               List<String> sourceFullIdPaths, ScanStrategy<E, P, R> strategy) {
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
        MediaScanContext ctx = new MediaScanContext(directory, source, force, batchTime, username, idToName,
                folderFullIdPath, sourceFullIdPaths);
        List<E> entities = strategy.group(ctx, nodes);
        // 阶段一：全部实体「事务外探测 + 事务内锚定 upsert/改挂」先行；跨实体移动的子项由目标实体按锚认领
        Map<E, P> prepared = new LinkedHashMap<>();
        Map<E, R> reconciled = new LinkedHashMap<>();
        boolean partial = false;
        for (E entity : entities) {
            if (mediaTaskSupport.isCancelled(directory.getId())) {
                return SourceOutcome.CANCELLED;
            }
            try {
                P prepare = strategy.prepare(ctx, entity);
                prepared.put(entity, prepare);
                reconciled.put(entity, strategy.upsert(ctx, entity, prepare));
            } catch (ScanCancelledException e) {
                return SourceOutcome.CANCELLED;
            } catch (Exception e) {
                log.warn("条目 upsert 失败: {}", strategy.entityLabel(entity), e);
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
        // 阶段二：全部实体 upsert 完成后，按实体删除亲眼确认消失的子项（跨实体移动的已被目标认领）
        for (E entity : entities) {
            try {
                strategy.deleteUnseen(ctx, entity, prepared.get(entity), reconciled.get(entity));
            } catch (Exception e) {
                log.warn("条目即时删除失败: {}", strategy.entityLabel(entity), e);
                partial = true;
            }
        }
        if (partial) {
            return SourceOutcome.PARTIAL;
        }
        // 外部字幕关联重建（挂到文件明细行/other 行，issue #19；阶段二删除完成后执行）
        rebuildSubtitles(ctx, nodes, strategy);
        return SourceOutcome.OK;
    }

    /**
     * 重建来源目录下各文件明细行的外部字幕关联（阶段二删除完成后执行，file_id 指向明细行/other 行 ID）。
     */
    private <E, P, R> void rebuildSubtitles(MediaScanContext ctx, List<FileNode> nodes,
                                            ScanStrategy<E, P, R> strategy) {
        mediaSubtitleSupport.rebuildForSource(strategy.subtitleRefs(ctx), nodes);
    }

    /**
     * ffprobe 探测单个文件并吞掉异常（探测失败仅记日志，不中断扫描）。
     */
    public MediaProbeResult probeQuietly(FileNode file, String username, Map<String, String> idToName) {
        try {
            return mediaScanSupport.probeFile(file, username, idToName);
        } catch (Exception e) {
            log.warn("ffprobe 探测失败: {}, {}", file.getName(), e.getMessage());
            return null;
        }
    }
}
