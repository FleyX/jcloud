package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.service.support.MediaScanDriverSupport.FileProbe;
import com.fleyx.jcloud.service.support.MediaScanDriverSupport.MediaScanContext;
import com.fleyx.jcloud.service.support.MediaScanDriverSupport.ScanStrategy;
import com.fleyx.jcloud.service.support.MediaSubtitleSupport.FileRef;
import com.fleyx.jcloud.service.support.MediaTvReconcileSupport.ReconcileResult;
import com.fleyx.jcloud.service.support.MediaTvReconcileSupport.SeasonFiles;
import com.fleyx.jcloud.service.support.MediaTvReconcileSupport.SeriesPrepare;
import com.fleyx.jcloud.util.MediaFileNameParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 电视库新模型扫描策略（ADR 0021 / issue #17）。
 * <p>
 * 票据 07 共享骨架重构后仅做差异装配：严格三层「来源目录/剧/季/集」识别（剧文件夹下散落视频直接丢弃、
 * 无虚拟季、超过三层的文件忽略）、剧分组与事务外探测预计算；scanDirectory/scanSource 双循环、批次清理
 * 三道闸等公共编排由 {@link MediaScanDriverSupport} 独占。两阶段语义不变（issue #17 F1）：
 * 阶段一按剧「事务外探测 + 事务内锚定 upsert/改挂」先行，同一来源全部剧的 upsert 完成后再进入阶段二
 * 按剧执行未见子项即时删除；来源部分失败（含掉线、剧 upsert 失败）时阶段二整体不做删除。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaTvScanSupport implements ScanStrategy<MediaTvScanSupport.TvScanEntity,
        SeriesPrepare, ReconcileResult> {

    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaEpisodeFileMapper mediaEpisodeFileMapper;
    private final MediaScanSupport mediaScanSupport;
    private final MediaScanDriverSupport mediaScanDriverSupport;
    private final MediaTvReconcileSupport mediaTvReconcileSupport;
    private final MediaTvCascadeSupport mediaTvCascadeSupport;
    private final MediaMetadataCompleteSupport mediaMetadataCompleteSupport;

    /** 单剧扫描实体：剧文件夹 + 其下按季分组的集文件。 */
    public record TvScanEntity(FileNode seriesFolder,
                               List<MediaTvReconcileSupport.SeasonFiles> seasonFilesList) {
    }

    @Override
    public List<TvScanEntity> group(MediaScanContext ctx, List<FileNode> nodes) {
        return groupBySeries(nodes, ctx.sourceFullIdPath()).entrySet().stream()
                .map(e -> new TvScanEntity(e.getKey(), e.getValue()))
                .toList();
    }

    @Override
    public MediaTvReconcileSupport.SeriesPrepare prepare(MediaScanContext ctx, TvScanEntity entity) {
        return prepareSeries(ctx, entity.seriesFolder(), entity.seasonFilesList());
    }

    @Override
    public MediaTvReconcileSupport.ReconcileResult upsert(MediaScanContext ctx, TvScanEntity entity,
                                                          MediaTvReconcileSupport.SeriesPrepare prepare) {
        return mediaTvReconcileSupport.upsertSeries(ctx, entity.seriesFolder(), entity.seasonFilesList(), prepare);
    }

    @Override
    public void deleteUnseen(MediaScanContext ctx, TvScanEntity entity,
                             MediaTvReconcileSupport.SeriesPrepare prepare,
                             MediaTvReconcileSupport.ReconcileResult result) {
        mediaTvReconcileSupport.deleteUnseenSeriesChildren(ctx, prepare, result);
    }

    @Override
    public String entityLabel(TvScanEntity entity) {
        return entity.seriesFolder().getName();
    }

    @Override
    public List<FileRef> subtitleRefs(MediaScanContext ctx) {
        List<String> seriesIds = mediaSeriesMapper.selectList(new LambdaQueryWrapper<MediaSeries>()
                        .eq(MediaSeries::getDirectoryId, ctx.directory().getId())
                        .eq(MediaSeries::getSourceId, ctx.source().getId())
                        .select(MediaSeries::getId))
                .stream().map(MediaSeries::getId).toList();
        if (seriesIds.isEmpty()) {
            return List.of();
        }
        List<String> episodeIds = mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                        .in(MediaEpisode::getSeriesId, seriesIds)
                        .select(MediaEpisode::getId))
                .stream().map(MediaEpisode::getId).toList();
        if (episodeIds.isEmpty()) {
            return List.of();
        }
        List<MediaEpisodeFile> files = mediaEpisodeFileMapper.selectList(
                new LambdaQueryWrapper<MediaEpisodeFile>().in(MediaEpisodeFile::getEpisodeId, episodeIds));
        return files.stream()
                .map(f -> new FileRef(f.getId(), f.getFileNodeId()))
                .toList();
    }

    @Override
    public List<String> staleIds(String directoryId, Set<String> qualifiedSourceIds, LocalDateTime batchTime) {
        return mediaSeriesMapper.selectList(new LambdaQueryWrapper<MediaSeries>()
                        .eq(MediaSeries::getDirectoryId, directoryId)
                        .in(MediaSeries::getSourceId, qualifiedSourceIds)
                        .and(w -> w.isNull(MediaSeries::getScanTime)
                                .or().lt(MediaSeries::getScanTime, batchTime))
                        .select(MediaSeries::getId))
                .stream().map(MediaSeries::getId).toList();
    }

    @Override
    public void deleteStale(List<String> ids) {
        if (!ids.isEmpty()) {
            log.info("批次清理删除消失的剧: count={}", ids.size());
            mediaTvCascadeSupport.deleteSeriesCascade(ids);
        }
    }

    @Override
    public void afterBatchCleanup(String directoryId) {
        mediaMetadataCompleteSupport.refreshSeriesCompleteByDirectory(directoryId);
    }

    /**
     * 事务外预计算（issue #17 F2）：按锚读存量行，对「新文件或哈希变化或强制全量」的文件执行 ffprobe 探测。
     * 探测可能全量下载远程文件，必须在进入 {@link MediaTvReconcileSupport#upsertSeries} 事务前完成，
     * 避免长占数据库连接；哈希未变的文件跳过探测，事务内同样按哈希命中跳过。哈希始终写入探测结果供 upsert 使用。
     */
    private MediaTvReconcileSupport.SeriesPrepare prepareSeries(MediaScanContext ctx, FileNode seriesFolder,
                                                                List<MediaTvReconcileSupport.SeasonFiles> seasonFilesList) {
        MediaSeries series = mediaSeriesMapper.selectOne(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getFolderNodeId, seriesFolder.getId()));
        List<MediaSeason> existingSeasons = new ArrayList<>();
        List<MediaEpisode> existingEpisodes = new ArrayList<>();
        List<MediaEpisodeFile> existingFiles = new ArrayList<>();
        if (series != null) {
            existingSeasons = mediaSeasonMapper.selectList(new LambdaQueryWrapper<MediaSeason>()
                    .eq(MediaSeason::getSeriesId, series.getId()));
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
        Map<String, FileProbe> probeByFileId = new HashMap<>();
        for (MediaTvReconcileSupport.SeasonFiles seasonFiles : seasonFilesList) {
            for (FileNode file : seasonFiles.videoFiles()) {
                if (MediaFileNameParser.parse(file.getName(), null, null).episodeNo() == null) {
                    continue; // 无集号文件丢弃，无需探测
                }
                String fileHash = mediaScanSupport.computeFileHash(file, ctx.source().getId(),
                        ctx.sourceFullIdPath(), ctx.idToName());
                MediaEpisodeFile row = fileByNodeId.get(file.getId());
                boolean probeNeeded = row == null || ctx.force() || !Objects.equals(row.getFileHash(), fileHash);
                probeByFileId.put(file.getId(), new FileProbe(fileHash,
                        probeNeeded ? mediaScanDriverSupport.probeQuietly(file, ctx.username(), ctx.idToName())
                                : null));
            }
        }
        return new MediaTvReconcileSupport.SeriesPrepare(series == null ? null : series.getId(),
                existingSeasons, existingEpisodes, existingFiles, fileByNodeId, episodeById, probeByFileId);
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
