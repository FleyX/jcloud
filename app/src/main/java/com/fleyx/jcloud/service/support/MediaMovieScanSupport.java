package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.service.support.MediaScanDriverSupport.FileProbe;
import com.fleyx.jcloud.service.support.MediaScanDriverSupport.MediaScanContext;
import com.fleyx.jcloud.service.support.MediaScanDriverSupport.ScanStrategy;
import com.fleyx.jcloud.service.support.MediaSubtitleSupport.FileRef;
import com.fleyx.jcloud.service.support.MediaMovieReconcileSupport.MoviePrepare;
import com.fleyx.jcloud.service.support.MediaMovieReconcileSupport.ReconcileResult;
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
 * 电影库新模型扫描策略（ADR 0021 / issue #18）。
 * <p>
 * 票据 07 共享骨架重构后仅做差异装配：识别规则（「直接包含视频文件的目录即一部电影」、来源根目录
 * 散视频丢弃、Jellyfin extras 目录整棵跳过、电影文件夹内子目录视频不入库、同文件夹多视频聚合为
 * 多版本）与电影分组、事务外探测预计算；scanDirectory/scanSource 双循环、批次清理三道闸等公共编排
 * 由 {@link MediaScanDriverSupport} 独占。两阶段语义不变（issue #17 F1 同款）：阶段一按电影
 * 「事务外探测 + 事务内锚定 upsert/改挂」先行，同一来源全部电影的 upsert 完成后再进入阶段二按电影
 * 删除亲眼确认消失的明细行；来源部分失败（含掉线、电影 upsert 失败）时阶段二整体不做删除。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaMovieScanSupport implements ScanStrategy<MediaMovieScanSupport.MovieScanEntity,
        MoviePrepare, ReconcileResult> {


    /**
     * Jellyfin extras 目录名清单（规范化后比对：小写并去除非字母数字，兼容大小写/空格/下划线差异）。
     */
    private static final Set<String> EXTRAS_DIR_NAMES = Set.of(
            "extras", "behindthescenes", "deletedscenes", "interviews", "scenes", "samples",
            "shorts", "featurettes", "clips", "trailers", "other", "backdrops", "thememusic");

    private final MediaMovieMapper mediaMovieMapper;
    private final MediaMovieFileMapper mediaMovieFileMapper;
    private final MediaScanSupport mediaScanSupport;
    private final MediaScanDriverSupport mediaScanDriverSupport;
    private final MediaMovieReconcileSupport mediaMovieReconcileSupport;
    private final MediaMovieCascadeSupport mediaMovieCascadeSupport;
    private final MediaMetadataCompleteSupport mediaMetadataCompleteSupport;

    /** 单电影扫描实体：电影文件夹 + 其下全部视频文件（多版本聚合）。 */
    public record MovieScanEntity(FileNode movieFolder, List<FileNode> videoFiles) {
    }

    @Override
    public List<MovieScanEntity> group(MediaScanContext ctx, List<FileNode> nodes) {
        return groupByMovieFolder(nodes, ctx.sourceFullIdPath()).entrySet().stream()
                .map(e -> new MovieScanEntity(e.getKey(), e.getValue()))
                .toList();
    }

    @Override
    public MoviePrepare prepare(MediaScanContext ctx, MovieScanEntity entity) {
        return prepareMovie(ctx, entity.movieFolder(), entity.videoFiles());
    }

    @Override
    public ReconcileResult upsert(MediaScanContext ctx, MovieScanEntity entity, MoviePrepare prepare) {
        return mediaMovieReconcileSupport.upsertMovie(ctx, entity.movieFolder(), entity.videoFiles(), prepare);
    }

    @Override
    public void deleteUnseen(MediaScanContext ctx, MovieScanEntity entity, MoviePrepare prepare,
                             ReconcileResult result) {
        mediaMovieReconcileSupport.deleteUnseenMovieFiles(ctx, prepare, result);
    }

    @Override
    public String entityLabel(MovieScanEntity entity) {
        return entity.movieFolder().getName();
    }

    @Override
    public List<FileRef> subtitleRefs(MediaScanContext ctx) {
        List<String> movieIds = mediaMovieMapper.selectList(new LambdaQueryWrapper<MediaMovie>()
                        .eq(MediaMovie::getDirectoryId, ctx.directory().getId())
                        .eq(MediaMovie::getSourceId, ctx.source().getId())
                        .select(MediaMovie::getId))
                .stream().map(MediaMovie::getId).toList();
        if (movieIds.isEmpty()) {
            return List.of();
        }
        List<MediaMovieFile> files = mediaMovieFileMapper.selectList(new LambdaQueryWrapper<MediaMovieFile>()
                .in(MediaMovieFile::getMovieId, movieIds));
        return files.stream()
                .map(f -> new FileRef(f.getId(), f.getFileNodeId()))
                .toList();
    }

    @Override
    public List<String> staleIds(String directoryId, Set<String> qualifiedSourceIds, LocalDateTime batchTime) {
        return mediaMovieMapper.selectList(new LambdaQueryWrapper<MediaMovie>()
                        .eq(MediaMovie::getDirectoryId, directoryId)
                        .in(MediaMovie::getSourceId, qualifiedSourceIds)
                        .and(w -> w.isNull(MediaMovie::getScanTime)
                                .or().lt(MediaMovie::getScanTime, batchTime))
                        .select(MediaMovie::getId))
                .stream().map(MediaMovie::getId).toList();
    }

    @Override
    public void deleteStale(List<String> ids) {
        if (!ids.isEmpty()) {
            log.info("批次清理删除消失的电影: count={}", ids.size());
            mediaMovieCascadeSupport.deleteMoviesCascade(ids);
        }
    }

    @Override
    public void afterBatchCleanup(String directoryId) {
        mediaMetadataCompleteSupport.refreshMoviesCompleteByDirectory(directoryId);
    }

    /**
     * 事务外预计算（issue #17 F2 同款）：按锚读存量行，对「新文件或哈希变化或强制全量」的文件执行
     * ffprobe 探测。探测可能全量下载远程文件，必须在进入 {@link MediaMovieReconcileSupport#upsertMovie}
     * 事务前完成，避免长占数据库连接；哈希未变的文件跳过探测，事务内同样按哈希命中跳过。
     * 哈希始终写入探测结果供 upsert 使用。
     */
    private MoviePrepare prepareMovie(MediaScanContext ctx, FileNode movieFolder, List<FileNode> videoFiles) {
        MediaMovie movie = mediaMovieMapper.selectOne(new LambdaQueryWrapper<MediaMovie>()
                .eq(MediaMovie::getFolderNodeId, movieFolder.getId()));
        List<MediaMovieFile> existingFiles = new ArrayList<>();
        if (movie != null) {
            existingFiles = mediaMovieFileMapper.selectList(new LambdaQueryWrapper<MediaMovieFile>()
                    .eq(MediaMovieFile::getMovieId, movie.getId()));
        }
        Map<String, MediaMovieFile> fileByNodeId = existingFiles.stream()
                .collect(Collectors.toMap(MediaMovieFile::getFileNodeId, Function.identity()));
        Map<String, FileProbe> probeByFileId = new HashMap<>();
        for (FileNode file : videoFiles) {
            String fileHash = mediaScanSupport.computeFileHash(file, ctx.source().getId(),
                    ctx.sourceFullIdPath(), ctx.idToName());
            MediaMovieFile row = fileByNodeId.get(file.getId());
            boolean probeNeeded = row == null || ctx.force() || !Objects.equals(row.getFileHash(), fileHash);
            probeByFileId.put(file.getId(), new FileProbe(fileHash,
                    probeNeeded ? mediaScanDriverSupport.probeQuietly(file, ctx.username(), ctx.idToName())
                            : null));
        }
        return new MoviePrepare(movie == null ? null : movie.getId(),
                existingFiles, fileByNodeId, probeByFileId);
    }

    /**
     * 电影文件夹分组：对每个视频文件定位其「直接包含视频文件的目录」（即最浅的、位于来源内且不含于
     * 任何其它直接含视频目录之下的祖先目录）。来源根散文件（父目录即来源根）丢弃；位于 extras 清单
     * 目录（含任意祖先）内的文件跳过；祖先目录中已存在直接含视频的目录时，本文件位于某部电影文件夹
     * 内部，不入库（电影文件夹不再向下递归找更多电影）。
     */
    private Map<FileNode, List<FileNode>> groupByMovieFolder(List<FileNode> nodes, String folderFullIdPath) {
        Map<String, FileNode> nodeById = nodes.stream()
                .collect(Collectors.toMap(FileNode::getId, Function.identity()));
        Set<String> extrasFolderIds = nodes.stream()
                .filter(n -> "folder".equals(n.getType()))
                .filter(n -> isExtrasDirName(n.getName()))
                .map(FileNode::getId)
                .collect(Collectors.toSet());
        Set<String> foldersWithDirectVideo = new HashSet<>();
        for (FileNode node : nodes) {
            if ("file".equals(node.getType()) && MediaFileNameParser.isVideoFile(node.getName())
                    && node.getParentId() != null) {
                foldersWithDirectVideo.add(node.getParentId());
            }
        }
        Map<FileNode, List<FileNode>> grouped = new LinkedHashMap<>();
        for (FileNode node : nodes) {
            if (!"file".equals(node.getType()) || !MediaFileNameParser.isVideoFile(node.getName())) {
                continue;
            }
            List<String> folderIds = mediaScanSupport.relativeFolderIds(node, folderFullIdPath);
            if (folderIds.isEmpty()) {
                log.debug("来源根目录下的散视频文件丢弃: {}", node.getName());
                continue;
            }
            if (folderIds.stream().anyMatch(extrasFolderIds::contains)) {
                log.debug("extras 目录内的视频文件跳过: {}", node.getName());
                continue;
            }
            FileNode movieFolder = nodeById.get(folderIds.getLast());
            if (movieFolder == null) {
                continue;
            }
            boolean insideMovieFolder = false;
            for (int i = 0; i < folderIds.size() - 1; i++) {
                if (foldersWithDirectVideo.contains(folderIds.get(i))) {
                    insideMovieFolder = true;
                    break;
                }
            }
            if (insideMovieFolder) {
                log.debug("电影文件夹子目录内的视频不入库: {}", node.getName());
                continue;
            }
            grouped.computeIfAbsent(movieFolder, k -> new ArrayList<>()).add(node);
        }
        return grouped;
    }

    /**
     * 目录名是否命中 Jellyfin extras 清单（规范化后比对：小写并去除非字母数字）。
     */
    private static boolean isExtrasDirName(String name) {
        if (name == null) {
            return false;
        }
        return EXTRAS_DIR_NAMES.contains(name.toLowerCase().replaceAll("[^a-z0-9]", ""));
    }
}
