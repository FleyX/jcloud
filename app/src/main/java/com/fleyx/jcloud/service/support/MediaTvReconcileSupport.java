package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.service.support.MediaReconcileDriverSupport.RowReconciler;
import com.fleyx.jcloud.service.support.MediaScanDriverSupport.FileProbe;
import com.fleyx.jcloud.service.support.MediaScanDriverSupport.MediaScanContext;
import com.fleyx.jcloud.util.MediaFileNameParser;
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
 * 电视库单剧 reconcile 支撑组件（新模型，ADR 0021 / issue #17）。
 * <p>
 * 与 {@link MediaTvScanSupport} 协作的两阶段编排：① {@link MediaTvScanSupport#prepareSeries}（事务外）
 * 预计算文件哈希与 ffprobe 探测，{@link #upsertSeries}（事务内）只做 DB 读写——剧/季按文件夹节点锚定
 * upsert，集按 (season_id, episode_no) 唯一键定位，集文件按视频文件节点锚定 upsert 或改挂；
 * ② 同一来源全部剧 upsert 完成后，{@link #deleteUnseenSeriesChildren}（事务内）按剧删除亲眼确认消失的
 * 季/集/集文件（连带元数据行）。票据 07 共享骨架重构后，剧行锚定 upsert 与明细行 reconcile 循环
 * 委托 {@link MediaReconcileDriverSupport}，集并入语义 {@link #placeEpisodeFile} 作为钩子保留。
 * 跨剧移动的子项在阶段②开始前已被目标剧按锚认领，配合 sourceFullIdPaths 删除守卫，
 * 进度与手动修正不丢失（issue #17 F1）；ffprobe 探测移出事务边界，长耗时远程下载不再占用
 * 数据库连接（issue #17 F2）。剧级级联删除见 {@link MediaTvCascadeSupport}（即时删除、批次清理与媒体库删除共用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaTvReconcileSupport {

    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaEpisodeFileMapper mediaEpisodeFileMapper;
    private final MediaTvCascadeSupport mediaTvCascadeSupport;
    private final MediaReconcileDriverSupport mediaReconcileDriverSupport;
    private final MediaWatchedLinkageSupport mediaWatchedLinkageSupport;

    /** 一季内待 reconcile 的集文件。 */
    public record SeasonFiles(FileNode seasonFolder, List<FileNode> videoFiles) {
    }

    /** 单剧 prepare 结果：存量行、锚映射与探测结果，供事务内 upsert 直接使用。 */
    public record SeriesPrepare(String seriesId, List<MediaSeason> existingSeasons,
                                List<MediaEpisode> existingEpisodes, List<MediaEpisodeFile> existingFiles,
                                Map<String, MediaEpisodeFile> fileByNodeId, Map<String, MediaEpisode> episodeById,
                                Map<String, FileProbe> probeByFileId) {
    }

    /** 单剧 upsert 结果：本轮亲眼确认的季/集/明细行 ID 与剧行 ID，供删除阶段使用。 */
    public record ReconcileResult(String seriesId, Set<String> seenSeasonIds, Set<String> seenEpisodeIds,
                                  Set<String> seenFileRowIds) {
    }

    /**
     * 事务内单剧 upsert（issue #17 F2：探测已由 {@link MediaTvScanSupport#prepareSeries} 在事务外完成，
     * 此处只做 DB 读写）。剧/季按文件夹节点锚定 upsert，集文件按锚 upsert 或改挂（进度保留）；不删除任何行——
     * 未见子项的删除由全部剧 upsert 完成后的 {@link #deleteUnseenSeriesChildren} 统一执行，
     * 跨剧移动时目标剧先按锚认领，源剧的删除才不误删（issue #17 F1）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReconcileResult upsertSeries(MediaScanContext ctx, FileNode seriesFolder,
                                        List<SeasonFiles> seasonFilesList, SeriesPrepare prepare) {
        MediaSeries series = upsertSeriesRow(ctx, seriesFolder, ctx.batchTime());
        Set<String> seenSeasonIds = new HashSet<>();
        Set<String> seenEpisodeIds = new HashSet<>();
        Set<String> seenFileRowIds = new HashSet<>();
        for (SeasonFiles seasonFiles : seasonFilesList) {
            MediaSeason season = upsertSeason(series, seasonFiles.seasonFolder(), prepare.existingSeasons());
            seenSeasonIds.add(season.getId());
            for (FileNode file : seasonFiles.videoFiles()) {
                reconcileEpisodeFile(ctx, series, season, file, prepare, seenEpisodeIds, seenFileRowIds);
            }
        }
        recalcLatestAddedTime(series.getId());
        return new ReconcileResult(series.getId(), seenSeasonIds, seenEpisodeIds, seenFileRowIds);
    }

    /**
     * 事务内单剧删除阶段：删除亲眼确认消失的季/集/明细（连带元数据行），并重算剧的最早文件修改时间。
     * 仅在本来源全部剧 upsert 成功（来源未部分失败）后调用（issue #17 F1）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteUnseenSeriesChildren(MediaScanContext ctx, SeriesPrepare prepare, ReconcileResult result) {
        mediaTvCascadeSupport.deleteUnseenChildren(prepare.existingSeasons(), prepare.existingEpisodes(),
                prepare.existingFiles(), result.seenSeasonIds(), result.seenEpisodeIds(), result.seenFileRowIds(),
                ctx.sourceFullIdPaths());
        recalcLatestAddedTime(result.seriesId());
        recalcMinFileLastModified(result.seriesId());
    }

    /**
     * 剧行锚定 upsert：锚 = 剧文件夹节点。重命名仅更新标题字段（manual 保留、非 manual 重置）；
     * 跨库/跨来源移动时改挂归属；批次扫描时间写入剧行。公共骨架委托
     * {@link MediaReconcileDriverSupport#upsertAnchoredRow}。
     */
    private MediaSeries upsertSeriesRow(MediaScanContext ctx, FileNode seriesFolder, LocalDateTime batchTime) {
        String seriesName = MediaFileNameParser.cleanTitle(seriesFolder.getName());
        Integer releaseYear = MediaFileNameParser.parseYear(seriesFolder.getName());
        return mediaReconcileDriverSupport.upsertAnchoredRow(seriesName, releaseYear,
                MediaMetadataOwnerType.SERIES.getCode(),
                () -> mediaSeriesMapper.selectOne(new LambdaQueryWrapper<MediaSeries>()
                        .eq(MediaSeries::getFolderNodeId, seriesFolder.getId())),
                () -> {
                    MediaSeries series = new MediaSeries();
                    series.setUserId(ctx.directory().getUserId());
                    series.setDirectoryId(ctx.directory().getId());
                    series.setSourceId(ctx.source().getId());
                    series.setFolderNodeId(seriesFolder.getId());
                    series.setSeriesName(seriesName);
                    series.setReleaseYear(releaseYear);
                    series.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
                    series.setMetadataComplete(false);
                    series.setScanTime(batchTime);
                    mediaSeriesMapper.insert(series);
                    return series;
                },
                (series, resetMatch) -> mediaSeriesMapper.update(null, new LambdaUpdateWrapper<MediaSeries>()
                        .eq(MediaSeries::getId, series.getId())
                        .set(MediaSeries::getDirectoryId, ctx.directory().getId())
                        .set(MediaSeries::getSourceId, ctx.source().getId())
                        .set(MediaSeries::getSeriesName, seriesName)
                        .set(MediaSeries::getReleaseYear, releaseYear)
                        .set(MediaSeries::getScanTime, batchTime)
                        .set(resetMatch, MediaSeries::getMetadataId, null)
                        .set(resetMatch, MediaSeries::getMatchStatus, MediaMatchStatus.UNMATCHED.getCode())
                        .set(resetMatch, MediaSeries::getMetadataComplete, false)),
                MediaSeries::getId, MediaSeries::getSeriesName, MediaSeries::getReleaseYear,
                MediaSeries::getMatchStatus, MediaSeries::getMetadataId);
    }

    /**
     * 季行锚定 upsert：锚 = 季文件夹节点。重命名更新季号；跨剧移动时改挂到新剧（其下集随行）。
     */
    private MediaSeason upsertSeason(MediaSeries series, FileNode seasonFolder,
                                     List<MediaSeason> existingSeasons) {
        Integer seasonNo = MediaFileNameParser.parseSeasonNo(seasonFolder.getName());
        MediaSeason season = existingSeasons.stream()
                .filter(s -> s.getFolderNodeId().equals(seasonFolder.getId())).findFirst().orElse(null);
        if (season == null) {
            season = mediaSeasonMapper.selectOne(new LambdaQueryWrapper<MediaSeason>()
                    .eq(MediaSeason::getFolderNodeId, seasonFolder.getId()));
        }
        if (season == null) {
            season = new MediaSeason();
            season.setSeriesId(series.getId());
            season.setFolderNodeId(seasonFolder.getId());
            season.setSeasonNo(seasonNo);
            mediaSeasonMapper.insert(season);
            existingSeasons.add(season);
            return season;
        }
        MediaSeason anchored = season;
        if (existingSeasons.stream().noneMatch(s -> s.getId().equals(anchored.getId()))) {
            // 锚命中其他剧的季：跨剧移动，改挂到本剧，其下集一并随行
            existingSeasons.add(season);
        }
        boolean seriesChanged = !series.getId().equals(season.getSeriesId());
        if (seriesChanged || !Objects.equals(season.getSeasonNo(), seasonNo)) {
            mediaSeasonMapper.update(null, new LambdaUpdateWrapper<MediaSeason>()
                    .eq(MediaSeason::getId, season.getId())
                    .set(MediaSeason::getSeriesId, series.getId())
                    .set(MediaSeason::getSeasonNo, seasonNo));
            if (seriesChanged) {
                mediaEpisodeMapper.update(null, new LambdaUpdateWrapper<MediaEpisode>()
                        .eq(MediaEpisode::getSeasonId, season.getId())
                        .set(MediaEpisode::getSeriesId, series.getId()));
            }
            season.setSeriesId(series.getId());
            season.setSeasonNo(seasonNo);
        }
        return season;
    }

    /**
     * 集文件 reconcile：锚命中且未变化则跳过；否则按锚 upsert 或改挂（目标已有同号集时并入作为版本）。
     * 锚全局唯一：文件跨剧/跨库移动时，锚命中的行可能属于其他剧的集，此时直接改挂到本剧目标集，
     * 源剧被清空的集由源剧删除阶段（{@link #deleteUnseenSeriesChildren}）删除。
     * 循环骨架委托 {@link MediaReconcileDriverSupport#reconcileRow}，集并入语义见 {@link #placeEpisodeFile}。
     */
    private void reconcileEpisodeFile(MediaScanContext ctx, MediaSeries series, MediaSeason season,
                                      FileNode file, SeriesPrepare prepare,
                                      Set<String> seenEpisodeIds, Set<String> seenFileRowIds) {
        Integer episodeNo = MediaFileNameParser.parse(file.getName(), null, null).episodeNo();
        if (episodeNo == null) {
            log.debug("集号无法解析，文件丢弃: {}", file.getName());
            return;
        }
        mediaReconcileDriverSupport.reconcileRow(ctx, file, prepare.probeByFileId(),
                prepare.fileByNodeId(), prepare.existingFiles(), new RowReconciler<MediaEpisodeFile>() {
                    @Override
                    public String fileHash(MediaEpisodeFile row) {
                        return row.getFileHash();
                    }

                    @Override
                    public boolean hashHitExtraOk(MediaEpisodeFile row, MediaScanContext c, FileProbe fp) {
                        MediaEpisode anchored = prepare.episodeById().get(row.getEpisodeId());
                        if (anchored == null) {
                            anchored = mediaEpisodeMapper.selectById(row.getEpisodeId());
                        }
                        return anchored != null && anchored.getSeasonId().equals(season.getId())
                                && Objects.equals(anchored.getEpisodeNo(), episodeNo);
                    }

                    @Override
                    public void markSeen(MediaEpisodeFile row) {
                        seenFileRowIds.add(row.getId());
                        seenEpisodeIds.add(row.getEpisodeId());
                    }

                    @Override
                    public MediaEpisodeFile findByFileNodeId(String fileNodeId) {
                        return mediaEpisodeFileMapper.selectOne(new LambdaQueryWrapper<MediaEpisodeFile>()
                                .eq(MediaEpisodeFile::getFileNodeId, fileNodeId));
                    }

                    @Override
                    public MediaEpisodeFile newRow(FileNode f) {
                        MediaEpisodeFile row = new MediaEpisodeFile();
                        row.setFileNodeId(f.getId());
                        return row;
                    }

                    @Override
                    public void assignAndPersist(MediaEpisodeFile row, MediaScanContext c, FileNode f, FileProbe fp) {
                        MediaEpisode episode = placeEpisodeFile(series, season, episodeNo, row,
                                prepare.episodeById(), prepare.existingEpisodes(), prepare.existingFiles());
                        row.setEpisodeId(episode.getId());
                        row.setFileHash(fp.fileHash());
                        row.setFileSize(f.getSize());
                        row.setFileLastModified(f.getLastModified());
                        if (fp.probe() != null) {
                            row.setDurationMs(fp.probe().durationMs());
                            row.setContainer(fp.probe().container());
                            row.setVideoCodec(fp.probe().videoCodec());
                            row.setAudioCodec(fp.probe().audioCodec());
                            row.setWidth(fp.probe().width());
                            row.setHeight(fp.probe().height());
                        }
                        if (row.getId() == null) {
                            mediaEpisodeFileMapper.insert(row);
                        } else {
                            mediaEpisodeFileMapper.updateById(row);
                        }
                    }
                });
    }

    /**
     * 定位集文件应归属的集行：位置未变返回原集；目标季已有同号集返回目标集（明细并入作为版本）；
     * 目标无同号集时，源集（含跨剧锚命中的集行）仅有这一个文件则直接改挂集行（进度保留），否则新建目标集。
     */
    private MediaEpisode placeEpisodeFile(MediaSeries series, MediaSeason season, Integer episodeNo,
                                          MediaEpisodeFile row, Map<String, MediaEpisode> episodeById,
                                          List<MediaEpisode> existingEpisodes, List<MediaEpisodeFile> existingFiles) {
        MediaEpisode current = row == null ? null : episodeById.get(row.getEpisodeId());
        if (current == null && row != null && row.getId() != null) {
            // 跨剧/跨库移动：锚命中的集行不属于本剧，从库中加载源集行
            current = mediaEpisodeMapper.selectById(row.getEpisodeId());
        }
        if (current != null && current.getSeasonId().equals(season.getId())
                && current.getEpisodeNo().equals(episodeNo)) {
            return current;
        }
        MediaEpisode target = existingEpisodes.stream()
                .filter(e -> e.getSeasonId().equals(season.getId()) && e.getEpisodeNo().equals(episodeNo))
                .findFirst().orElse(null);
        if (current == null || target != null) {
            if (target == null) {
                target = createEpisode(series, season, episodeNo, existingEpisodes, episodeById);
            }
            return target;
        }
        Long siblings = mediaEpisodeFileMapper.selectCount(new LambdaQueryWrapper<MediaEpisodeFile>()
                .eq(MediaEpisodeFile::getEpisodeId, current.getId())
                .ne(MediaEpisodeFile::getId, row.getId()));
        if (siblings != null && siblings == 0) {
            String srcSeasonId = current.getSeasonId();
            String srcSeriesId = current.getSeriesId();
            mediaEpisodeMapper.update(null, new LambdaUpdateWrapper<MediaEpisode>()
                    .eq(MediaEpisode::getId, current.getId())
                    .set(MediaEpisode::getSeriesId, series.getId())
                    .set(MediaEpisode::getSeasonId, season.getId())
                    .set(MediaEpisode::getEpisodeNo, episodeNo));
            current.setSeriesId(series.getId());
            current.setSeasonId(season.getId());
            current.setEpisodeNo(episodeNo);
            if (!episodeById.containsKey(current.getId())) {
                existingEpisodes.add(current);
                episodeById.put(current.getId(), current);
            }
            // 集改挂移动（watched 随行）：重算源与目标两侧父级（工单 02）
            mediaWatchedLinkageSupport.recomputeParents(srcSeasonId, srcSeriesId);
            mediaWatchedLinkageSupport.recomputeParents(season.getId(), series.getId());
            return current;
        }
        return createEpisode(series, season, episodeNo, existingEpisodes, episodeById);
    }

    MediaEpisode createEpisode(MediaSeries series, MediaSeason season, Integer episodeNo,
                               List<MediaEpisode> existingEpisodes, Map<String, MediaEpisode> episodeById) {
        MediaEpisode episode = new MediaEpisode();
        episode.setSeriesId(series.getId());
        episode.setSeasonId(season.getId());
        episode.setEpisodeNo(episodeNo);
        episode.setProgressMs(0L);
        mediaEpisodeMapper.insert(episode);
        existingEpisodes.add(episode);
        episodeById.put(episode.getId(), episode);
        // 新集默认未观看 → 父级重算：追更剧回到未看完（工单 02）
        mediaWatchedLinkageSupport.recomputeParents(season.getId(), series.getId());
        return episode;
    }

    /**
     * 重算剧的最早一集文件修改时间（添加时间排序依据）。
     */
    private void recalcMinFileLastModified(String seriesId) {
        List<String> episodeIds = mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                        .eq(MediaEpisode::getSeriesId, seriesId)
                        .select(MediaEpisode::getId))
                .stream().map(MediaEpisode::getId).toList();
        Long min = null;
        if (!episodeIds.isEmpty()) {
            min = mediaEpisodeFileMapper.selectList(new LambdaQueryWrapper<MediaEpisodeFile>()
                            .in(MediaEpisodeFile::getEpisodeId, episodeIds)
                            .isNotNull(MediaEpisodeFile::getFileLastModified)
                            .orderByAsc(MediaEpisodeFile::getFileLastModified)
                            .last("limit 1"))
                    .stream().map(MediaEpisodeFile::getFileLastModified).filter(Objects::nonNull)
                    .findFirst().orElse(null);
        }
        mediaSeriesMapper.update(null, new LambdaUpdateWrapper<MediaSeries>()
                .eq(MediaSeries::getId, seriesId)
                .set(MediaSeries::getMinFileLastModified, min));
    }

    /**
     * 重算剧集当前集文件明细的最新入库时间，包含该剧的所有季和特别篇。
     */
    private void recalcLatestAddedTime(String seriesId) {
        List<String> episodeIds = mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                        .eq(MediaEpisode::getSeriesId, seriesId)
                        .select(MediaEpisode::getId))
                .stream().map(MediaEpisode::getId).toList();
        LocalDateTime max = null;
        if (!episodeIds.isEmpty()) {
            max = mediaEpisodeFileMapper.selectList(new LambdaQueryWrapper<MediaEpisodeFile>()
                            .in(MediaEpisodeFile::getEpisodeId, episodeIds)
                            .isNotNull(MediaEpisodeFile::getCreateTime)
                            .orderByDesc(MediaEpisodeFile::getCreateTime)
                            .last("limit 1"))
                    .stream().map(MediaEpisodeFile::getCreateTime).filter(Objects::nonNull)
                    .findFirst().orElse(null);
        }
        mediaSeriesMapper.update(null, new LambdaUpdateWrapper<MediaSeries>()
                .eq(MediaSeries::getId, seriesId)
                .set(MediaSeries::getLatestAddedTime, max));
    }
}
