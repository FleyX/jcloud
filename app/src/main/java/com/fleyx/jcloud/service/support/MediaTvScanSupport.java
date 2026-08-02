package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaScanOutcome;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaSeasonV2Mapper;
import com.fleyx.jcloud.mapper.MediaSeriesV2Mapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaSeasonV2;
import com.fleyx.jcloud.model.po.MediaSeriesV2;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.MediaFileNameParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 电视库新模型扫描编排支撑组件（ADR 0021 / issue #17）。
 * <p>
 * 严格三层「来源目录/剧/季/集」识别：剧文件夹下散落视频直接丢弃（取消旧的「归入 S01」），
 * 无虚拟季，超过三层的文件忽略。逐来源两阶段编排（issue #17 F1）：阶段一按剧「事务外探测 + 事务内锚定
 * upsert/改挂」先行，同一来源全部剧的 upsert 完成后再进入阶段二按剧执行未见子项即时删除——跨剧移动的子项
 * 已被目标剧按锚认领，进度不丢失；来源部分失败（含掉线、剧 upsert 失败）时阶段二整体不做删除。
 * 扫描末尾按批次扫描时间执行三道闸批次清理：① 仅完整成功（无部分失败）的扫描执行；② 仅本轮「可达且完整扫完」
 * 的来源目录参与结算；③ 仅删除有资格来源下 scan_time 早于批次时间的剧行（级联）。清理按 directory_id 库内闭环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaTvScanSupport {

    private final FileMapper fileMapper;
    private final MediaSeriesV2Mapper mediaSeriesV2Mapper;
    private final MediaSeasonV2Mapper mediaSeasonV2Mapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaEpisodeFileMapper mediaEpisodeFileMapper;
    private final MediaScanSupport mediaScanSupport;
    private final MediaTvReconcileSupport mediaTvReconcileSupport;
    private final MediaTvCascadeSupport mediaTvCascadeSupport;
    private final MediaSubtitleSupport mediaSubtitleSupport;
    private final MediaTaskSupport mediaTaskSupport;

    private enum SourceOutcome {
        OK, PARTIAL, CANCELLED
    }

    /**
     * 电视库整轮扫描：批次时间取扫描开始时刻，逐来源扫描并在完整成功时执行批次清理。
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
        // 本库全部可达来源目录的完整物化路径：即时删除的跨剧移动守卫（文件仍在库内来源下则不删，留给新父级改挂）
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
     * 批次清理：仅删除本轮完整扫完的来源目录下（闸②）、scan_time 早于批次时间的剧行（闸③），级联删除。
     */
    private void batchCleanup(MediaDirectory directory, Set<String> qualifiedSourceIds, LocalDateTime batchTime) {
        if (qualifiedSourceIds.isEmpty()) {
            return;
        }
        List<String> staleIds = mediaSeriesV2Mapper.selectList(new LambdaQueryWrapper<MediaSeriesV2>()
                        .eq(MediaSeriesV2::getDirectoryId, directory.getId())
                        .in(MediaSeriesV2::getSourceId, qualifiedSourceIds)
                        .and(w -> w.isNull(MediaSeriesV2::getScanTime)
                                .or().lt(MediaSeriesV2::getScanTime, batchTime))
                        .select(MediaSeriesV2::getId))
                .stream().map(MediaSeriesV2::getId).toList();
        if (!staleIds.isEmpty()) {
            log.info("批次清理删除消失的剧: directory={}, count={}", directory.getId(), staleIds.size());
            mediaTvCascadeSupport.deleteSeriesCascade(staleIds);
        }
    }

    /**
     * 扫描单个来源目录：加载子树、严格三层分组，两阶段 reconcile。
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
        MediaTvReconcileSupport.TvScanContext ctx = new MediaTvReconcileSupport.TvScanContext(
                directory, source, force, batchTime, username, idToName, folderFullIdPath, sourceFullIdPaths);

        Map<FileNode, List<MediaTvReconcileSupport.SeasonFiles>> grouped = groupBySeries(nodes, folderFullIdPath);
        // 阶段一：全部剧的「事务外探测 + 事务内锚定 upsert/改挂」先行；跨剧移动的子项由目标剧按锚认领
        Map<FileNode, MediaTvReconcileSupport.SeriesPrepare> prepared = new LinkedHashMap<>();
        Map<FileNode, MediaTvReconcileSupport.ReconcileResult> reconciled = new LinkedHashMap<>();
        boolean partial = false;
        for (Map.Entry<FileNode, List<MediaTvReconcileSupport.SeasonFiles>> entry : grouped.entrySet()) {
            if (mediaTaskSupport.isCancelled(directory.getId())) {
                return SourceOutcome.CANCELLED;
            }
            try {
                MediaTvReconcileSupport.SeriesPrepare prepare =
                        prepareSeries(ctx, entry.getKey(), entry.getValue());
                prepared.put(entry.getKey(), prepare);
                reconciled.put(entry.getKey(),
                        mediaTvReconcileSupport.upsertSeries(ctx, entry.getKey(), entry.getValue(), prepare));
            } catch (Exception e) {
                log.warn("剧 upsert 失败: {}", entry.getKey().getName(), e);
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
        // 阶段二：全部剧 upsert 完成后，按剧删除亲眼确认消失的季/集/明细（连带元数据行）
        for (Map.Entry<FileNode, MediaTvReconcileSupport.ReconcileResult> entry : reconciled.entrySet()) {
            try {
                mediaTvReconcileSupport.deleteUnseenSeriesChildren(ctx,
                        prepared.get(entry.getKey()), entry.getValue());
            } catch (Exception e) {
                log.warn("剧即时删除失败: {}", entry.getKey().getName(), e);
                partial = true;
            }
        }
        if (partial) {
            return SourceOutcome.PARTIAL;
        }
        // 外部字幕关联重建（挂到集文件明细行，issue #19）
        rebuildSubtitles(ctx, nodes);
        return SourceOutcome.OK;
    }

    /**
     * 重建来源目录下集文件明细行的外部字幕关联（阶段二删除完成后执行，file_id 指向明细行 ID）。
     */
    private void rebuildSubtitles(MediaTvReconcileSupport.TvScanContext ctx, List<FileNode> nodes) {
        List<String> seriesIds = mediaSeriesV2Mapper.selectList(new LambdaQueryWrapper<MediaSeriesV2>()
                        .eq(MediaSeriesV2::getDirectoryId, ctx.directory().getId())
                        .eq(MediaSeriesV2::getSourceId, ctx.source().getId())
                        .select(MediaSeriesV2::getId))
                .stream().map(MediaSeriesV2::getId).toList();
        if (seriesIds.isEmpty()) {
            return;
        }
        List<String> episodeIds = mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                        .in(MediaEpisode::getSeriesId, seriesIds)
                        .select(MediaEpisode::getId))
                .stream().map(MediaEpisode::getId).toList();
        if (episodeIds.isEmpty()) {
            return;
        }
        List<MediaEpisodeFile> files = mediaEpisodeFileMapper.selectList(
                new LambdaQueryWrapper<MediaEpisodeFile>().in(MediaEpisodeFile::getEpisodeId, episodeIds));
        List<MediaSubtitleSupport.FileRef> refs = files.stream()
                .map(f -> new MediaSubtitleSupport.FileRef(f.getId(), f.getFileNodeId()))
                .toList();
        mediaSubtitleSupport.rebuildForSource(refs, nodes);
    }

    /**
     * 事务外预计算（issue #17 F2）：按锚读存量行，对「新文件或哈希变化或强制全量」的文件执行 ffprobe 探测。
     * 探测可能全量下载远程文件，必须在进入 {@link MediaTvReconcileSupport#upsertSeries} 事务前完成，
     * 避免长占数据库连接；哈希未变的文件跳过探测，事务内同样按哈希命中跳过。哈希始终写入探测结果供 upsert 使用。
     */
    private MediaTvReconcileSupport.SeriesPrepare prepareSeries(MediaTvReconcileSupport.TvScanContext ctx,
                                                                FileNode seriesFolder,
                                                                List<MediaTvReconcileSupport.SeasonFiles> seasonFilesList) {
        MediaSeriesV2 series = mediaSeriesV2Mapper.selectOne(new LambdaQueryWrapper<MediaSeriesV2>()
                .eq(MediaSeriesV2::getFolderNodeId, seriesFolder.getId()));
        List<MediaSeasonV2> existingSeasons = new ArrayList<>();
        List<MediaEpisode> existingEpisodes = new ArrayList<>();
        List<MediaEpisodeFile> existingFiles = new ArrayList<>();
        if (series != null) {
            existingSeasons = mediaSeasonV2Mapper.selectList(new LambdaQueryWrapper<MediaSeasonV2>()
                    .eq(MediaSeasonV2::getSeriesId, series.getId()));
            existingEpisodes = mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                    .eq(MediaEpisode::getSeriesId, series.getId()));
            if (!existingEpisodes.isEmpty()) {
                existingFiles = mediaEpisodeFileMapper.selectList(new LambdaQueryWrapper<MediaEpisodeFile>()
                        .in(MediaEpisodeFile::getEpisodeId,
                                existingEpisodes.stream().map(MediaEpisode::getId).toList()));
            }
        }
        Map<String, MediaEpisodeFile> fileByNodeId = existingFiles.stream()
                .collect(Collectors.toMap(MediaEpisodeFile::getFileNodeId, Function.identity()));
        Map<String, MediaEpisode> episodeById = existingEpisodes.stream()
                .collect(Collectors.toMap(MediaEpisode::getId, Function.identity()));
        Map<String, MediaTvReconcileSupport.FileProbe> probeByFileId = new HashMap<>();
        for (MediaTvReconcileSupport.SeasonFiles seasonFiles : seasonFilesList) {
            for (FileNode file : seasonFiles.videoFiles()) {
                if (MediaFileNameParser.parse(file.getName(), null, null).episodeNo() == null) {
                    continue; // 无集号文件丢弃，无需探测
                }
                String fileHash = mediaScanSupport.computeFileHash(file, ctx.source().getId(),
                        ctx.sourceFullIdPath(), ctx.idToName());
                MediaEpisodeFile row = fileByNodeId.get(file.getId());
                boolean probeNeeded = row == null || ctx.force() || !Objects.equals(row.getFileHash(), fileHash);
                probeByFileId.put(file.getId(), new MediaTvReconcileSupport.FileProbe(fileHash,
                        probeNeeded ? probeQuietly(file, ctx.username(), ctx.idToName()) : null));
            }
        }
        return new MediaTvReconcileSupport.SeriesPrepare(series == null ? null : series.getId(),
                existingSeasons, existingEpisodes, existingFiles, fileByNodeId, episodeById, probeByFileId);
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
     * 严格三层分组：仅「来源目录/剧文件夹/季文件夹/集文件」结构的视频文件参与 reconcile；
     * 剧文件夹下散落视频丢弃，根目录散文件与超过三层的文件忽略。
     */
    private Map<FileNode, List<MediaTvReconcileSupport.SeasonFiles>> groupBySeries(List<FileNode> nodes,
                                                                                   String folderFullIdPath) {
        Map<String, FileNode> nodeById = nodes.stream()
                .collect(Collectors.toMap(FileNode::getId, Function.identity()));
        Map<FileNode, Map<FileNode, List<FileNode>>> grouped = new LinkedHashMap<>();
        for (FileNode node : nodes) {
            if (!"file".equals(node.getType()) || !MediaFileNameParser.isVideoFile(node.getName())) {
                continue;
            }
            List<String> folderIds = mediaScanSupport.relativeFolderIds(node, folderFullIdPath);
            if (folderIds.size() != 2) {
                log.debug("不符合三层结构，文件忽略: {}", node.getName());
                continue;
            }
            FileNode seriesFolder = nodeById.get(folderIds.get(0));
            FileNode seasonFolder = nodeById.get(folderIds.get(1));
            if (seriesFolder == null || seasonFolder == null) {
                continue;
            }
            grouped.computeIfAbsent(seriesFolder, k -> new LinkedHashMap<>())
                    .computeIfAbsent(seasonFolder, k -> new ArrayList<>())
                    .add(node);
        }
        Map<FileNode, List<MediaTvReconcileSupport.SeasonFiles>> result = new LinkedHashMap<>();
        for (Map.Entry<FileNode, Map<FileNode, List<FileNode>>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), entry.getValue().entrySet().stream()
                    .map(e -> new MediaTvReconcileSupport.SeasonFiles(e.getKey(), e.getValue()))
                    .toList());
        }
        return result;
    }
}
