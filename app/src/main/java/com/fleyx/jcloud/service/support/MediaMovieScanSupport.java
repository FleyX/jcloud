package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaScanOutcome;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.service.support.MediaMovieReconcileSupport.FileProbe;
import com.fleyx.jcloud.service.support.MediaMovieReconcileSupport.MoviePrepare;
import com.fleyx.jcloud.service.support.MediaMovieReconcileSupport.MovieScanContext;
import com.fleyx.jcloud.service.support.MediaMovieReconcileSupport.ReconcileResult;
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
 * 电影库新模型扫描编排支撑组件（ADR 0021 / issue #18）。
 * <p>
 * 识别规则：「直接包含视频文件的目录即一部电影」，来源根目录到电影文件夹之间允许任意层级纯目录；
 * 父目录为来源根目录的散视频文件丢弃；Jellyfin extras 目录名清单（extras、behind the scenes、
 * deleted scenes、interviews、scenes、samples、shorts、featurettes、clips、trailers、other、
 * backdrops、theme-music，大小写不敏感）整棵跳过；电影文件夹内子目录（非 extras）的视频不入库
 * （电影文件夹不再向下递归找更多电影）。同一电影文件夹内多个视频聚合为一部电影的多版本。
 * <p>
 * 逐来源两阶段编排（与电视库 issue #17 同一套管线语义）：阶段一按电影「事务外探测 + 事务内锚定
 * upsert/改挂」先行，同一来源全部电影的 upsert 完成后再进入阶段二按电影删除亲眼确认消失的明细行——
 * 跨电影移动的明细已被目标电影按锚认领，进度不丢失；来源部分失败（含掉线、电影 upsert 失败）时
 * 阶段二整体不做删除。扫描末尾按批次扫描时间执行三道闸批次清理：① 仅完整成功（无部分失败）的扫描
 * 执行；② 仅本轮「可达且完整扫完」的来源目录参与结算；③ 仅删除有资格来源下 scan_time 早于批次
 * 时间的电影行（级联）。清理按 directory_id 库内闭环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaMovieScanSupport {

    /**
     * Jellyfin extras 目录名清单（规范化后比对：小写并去除非字母数字，兼容大小写/空格/下划线差异）。
     */
    private static final Set<String> EXTRAS_DIR_NAMES = Set.of(
            "extras", "behindthescenes", "deletedscenes", "interviews", "scenes", "samples",
            "shorts", "featurettes", "clips", "trailers", "other", "backdrops", "thememusic");

    private final FileMapper fileMapper;
    private final MediaMovieMapper mediaMovieMapper;
    private final MediaMovieFileMapper mediaMovieFileMapper;
    private final MediaScanSupport mediaScanSupport;
    private final MediaMovieReconcileSupport mediaMovieReconcileSupport;
    private final MediaMovieCascadeSupport mediaMovieCascadeSupport;
    private final MediaSubtitleSupport mediaSubtitleSupport;
    private final MediaTaskSupport mediaTaskSupport;
    private final MediaMetadataCompleteSupport mediaMetadataCompleteSupport;

    private enum SourceOutcome {
        OK, PARTIAL, CANCELLED
    }

    /**
     * 电影库整轮扫描：批次时间取扫描开始时刻，逐来源扫描并在完整成功时执行批次清理。
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
        // 本库全部可达来源目录的完整物化路径：即时删除的跨电影移动守卫（文件仍在库内来源下则不删，留给新父级改挂）
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
        // 三道闸：① 仅完整成功的扫描执行批次清理；清理后对本库条目重算完整性（级联删除连带删元数据行，必须在清理之后）
        if (!partial) {
            batchCleanup(directory, qualifiedSourceIds, batchTime);
            mediaMetadataCompleteSupport.refreshMoviesCompleteByDirectory(directory.getId());
            return MediaScanOutcome.COMPLETED;
        }
        return MediaScanOutcome.PARTIAL;
    }

    /**
     * 批次清理：仅删除本轮完整扫完的来源目录下（闸②）、scan_time 早于批次时间的电影行（闸③），级联删除。
     */
    private void batchCleanup(MediaDirectory directory, Set<String> qualifiedSourceIds, LocalDateTime batchTime) {
        if (qualifiedSourceIds.isEmpty()) {
            return;
        }
        List<String> staleIds = mediaMovieMapper.selectList(new LambdaQueryWrapper<MediaMovie>()
                        .eq(MediaMovie::getDirectoryId, directory.getId())
                        .in(MediaMovie::getSourceId, qualifiedSourceIds)
                        .and(w -> w.isNull(MediaMovie::getScanTime)
                                .or().lt(MediaMovie::getScanTime, batchTime))
                        .select(MediaMovie::getId))
                .stream().map(MediaMovie::getId).toList();
        if (!staleIds.isEmpty()) {
            log.info("批次清理删除消失的电影: directory={}, count={}", directory.getId(), staleIds.size());
            mediaMovieCascadeSupport.deleteMoviesCascade(staleIds);
        }
    }

    /**
     * 扫描单个来源目录：加载子树、按电影文件夹分组，两阶段 reconcile。
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
        MovieScanContext ctx = new MovieScanContext(directory, source, force, batchTime, username,
                idToName, folderFullIdPath, sourceFullIdPaths);

        Map<FileNode, List<FileNode>> grouped = groupByMovieFolder(nodes, folderFullIdPath);
        // 阶段一：全部电影的「事务外探测 + 事务内锚定 upsert/改挂」先行；跨电影移动的明细由目标电影按锚认领
        Map<FileNode, MoviePrepare> prepared = new LinkedHashMap<>();
        Map<FileNode, ReconcileResult> reconciled = new LinkedHashMap<>();
        boolean partial = false;
        for (Map.Entry<FileNode, List<FileNode>> entry : grouped.entrySet()) {
            if (mediaTaskSupport.isCancelled(directory.getId())) {
                return SourceOutcome.CANCELLED;
            }
            try {
                MoviePrepare prepare = prepareMovie(ctx, entry.getKey(), entry.getValue());
                prepared.put(entry.getKey(), prepare);
                reconciled.put(entry.getKey(),
                        mediaMovieReconcileSupport.upsertMovie(ctx, entry.getKey(), entry.getValue(), prepare));
            } catch (Exception e) {
                log.warn("电影 upsert 失败: {}", entry.getKey().getName(), e);
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
        // 阶段二：全部电影 upsert 完成后，按电影删除亲眼确认消失的明细行（跨电影移动的已被目标认领）
        for (Map.Entry<FileNode, ReconcileResult> entry : reconciled.entrySet()) {
            try {
                mediaMovieReconcileSupport.deleteUnseenMovieFiles(ctx,
                        prepared.get(entry.getKey()), entry.getValue());
            } catch (Exception e) {
                log.warn("电影即时删除失败: {}", entry.getKey().getName(), e);
                partial = true;
            }
        }
        if (partial) {
            return SourceOutcome.PARTIAL;
        }
        // 外部字幕关联重建（挂到电影文件明细行，issue #19）
        rebuildSubtitles(ctx, nodes);
        return SourceOutcome.OK;
    }

    /**
     * 重建来源目录下电影文件明细行的外部字幕关联（阶段二删除完成后执行，file_id 指向明细行 ID）。
     */
    private void rebuildSubtitles(MovieScanContext ctx, List<FileNode> nodes) {
        List<String> movieIds = mediaMovieMapper.selectList(new LambdaQueryWrapper<MediaMovie>()
                        .eq(MediaMovie::getDirectoryId, ctx.directory().getId())
                        .eq(MediaMovie::getSourceId, ctx.source().getId())
                        .select(MediaMovie::getId))
                .stream().map(MediaMovie::getId).toList();
        if (movieIds.isEmpty()) {
            return;
        }
        List<MediaMovieFile> files = mediaMovieFileMapper.selectList(new LambdaQueryWrapper<MediaMovieFile>()
                .in(MediaMovieFile::getMovieId, movieIds));
        List<MediaSubtitleSupport.FileRef> refs = files.stream()
                .map(f -> new MediaSubtitleSupport.FileRef(f.getId(), f.getFileNodeId()))
                .toList();
        mediaSubtitleSupport.rebuildForSource(refs, nodes);
    }

    /**
     * 事务外预计算（issue #17 F2 同款）：按锚读存量行，对「新文件或哈希变化或强制全量」的文件执行 ffprobe 探测。
     * 探测可能全量下载远程文件，必须在进入 {@link MediaMovieReconcileSupport#upsertMovie} 事务前完成，
     * 避免长占数据库连接；哈希未变的文件跳过探测，事务内同样按哈希命中跳过。哈希始终写入探测结果供 upsert 使用。
     */
    private MoviePrepare prepareMovie(MovieScanContext ctx, FileNode movieFolder, List<FileNode> videoFiles) {
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
                    probeNeeded ? probeQuietly(file, ctx.username(), ctx.idToName()) : null));
        }
        return new MoviePrepare(movie == null ? null : movie.getId(),
                existingFiles, fileByNodeId, probeByFileId);
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
