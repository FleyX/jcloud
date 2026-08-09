package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
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
 * 电影库单电影 reconcile 支撑组件（新模型，ADR 0021 / issue #18）。
 * <p>
 * 与 {@link MediaMovieScanSupport} 协作的两阶段编排：① {@link MediaMovieScanSupport#prepareMovie}（事务外）
 * 预计算文件哈希与 ffprobe 探测，{@link #upsertMovie}（事务内）只做 DB 读写——电影行按文件夹节点锚定
 * upsert，电影文件明细按视频文件节点锚定 upsert 或改挂（跨电影移动时目标电影按锚认领，进度保留）；
 * ② 同一来源全部电影 upsert 完成后，{@link #deleteUnseenMovieFiles}（事务内）删除亲眼确认消失的
 * 明细行。票据 07 共享骨架重构后，电影行锚定 upsert 与明细行 reconcile 循环委托
 * {@link MediaReconcileDriverSupport}。ffprobe 探测移出事务边界，长耗时远程下载不再占用
 * 数据库连接（issue #17 F2 同款）；明细行删除以 sourceFullIdPaths 守卫防跨电影移动竞态（issue #17 F1 同款）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaMovieReconcileSupport {

    private final MediaMovieMapper mediaMovieMapper;
    private final MediaMovieFileMapper mediaMovieFileMapper;
    private final MediaMovieCascadeSupport mediaMovieCascadeSupport;
    private final MediaReconcileDriverSupport mediaReconcileDriverSupport;

    /** 单电影 prepare 结果：存量明细、锚映射与探测结果，供事务内 upsert 直接使用。 */
    public record MoviePrepare(String movieId, List<MediaMovieFile> existingFiles,
                               Map<String, MediaMovieFile> fileByNodeId, Map<String, FileProbe> probeByFileId) {
    }

    /** 单电影 upsert 结果：本轮亲眼确认的明细行 ID 与电影行 ID，供删除阶段使用。 */
    public record ReconcileResult(String movieId, Set<String> seenFileRowIds) {
    }

    /**
     * 事务内单电影 upsert（探测已由 {@link MediaMovieScanSupport#prepareMovie} 在事务外完成，
     * 此处只做 DB 读写）。电影行按文件夹节点锚定 upsert，明细行按锚 upsert 或改挂（进度保留）；
     * 不删除任何行——未见明细的删除由全部电影 upsert 完成后的 {@link #deleteUnseenMovieFiles} 统一执行，
     * 跨电影移动时目标电影先按锚认领，源电影的删除才不误删（issue #17 F1 同款）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReconcileResult upsertMovie(MediaScanContext ctx, FileNode movieFolder,
                                       List<FileNode> videoFiles, MoviePrepare prepare) {
        MediaMovie movie = upsertMovieRow(ctx, movieFolder, ctx.batchTime());
        Set<String> seenFileRowIds = new HashSet<>();
        for (FileNode file : videoFiles) {
            reconcileMovieFile(ctx, movie, file, prepare, seenFileRowIds);
        }
        recalcAddedTime(movie.getId());
        return new ReconcileResult(movie.getId(), seenFileRowIds);
    }

    /**
     * 事务内单电影删除阶段：删除亲眼确认消失的明细行（连带不再承载的元数据行随电影行批次清理）。
     * 仅在本来源全部电影 upsert 成功（来源未部分失败）后调用（issue #17 F1 同款）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteUnseenMovieFiles(MediaScanContext ctx, MoviePrepare prepare, ReconcileResult result) {
        mediaMovieCascadeSupport.deleteUnseenFiles(prepare.existingFiles(), result.seenFileRowIds(),
                ctx.sourceFullIdPaths());
        recalcAddedTime(result.movieId());
    }

    /**
     * 电影行锚定 upsert：锚 = 电影文件夹节点。重命名仅更新 title/release_year（manual 保留、
     * 非 manual 重置匹配并清元数据）；跨库/跨来源移动时改挂归属；批次扫描时间写入电影行。
     * 公共骨架委托 {@link MediaReconcileDriverSupport#upsertAnchoredRow}。
     */
    private MediaMovie upsertMovieRow(MediaScanContext ctx, FileNode movieFolder, LocalDateTime batchTime) {
        String title = MediaFileNameParser.cleanTitle(movieFolder.getName());
        Integer releaseYear = MediaFileNameParser.parseYear(movieFolder.getName());
        return mediaReconcileDriverSupport.upsertAnchoredRow(title, releaseYear,
                MediaMetadataOwnerType.MOVIE.getCode(),
                () -> mediaMovieMapper.selectOne(new LambdaQueryWrapper<MediaMovie>()
                        .eq(MediaMovie::getFolderNodeId, movieFolder.getId())),
                () -> {
                    MediaMovie movie = new MediaMovie();
                    movie.setUserId(ctx.directory().getUserId());
                    movie.setDirectoryId(ctx.directory().getId());
                    movie.setSourceId(ctx.source().getId());
                    movie.setFolderNodeId(movieFolder.getId());
                    movie.setTitle(title);
                    movie.setReleaseYear(releaseYear);
                    movie.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
                    movie.setMetadataComplete(false);
                    movie.setProgressMs(0L);
                    movie.setScanTime(batchTime);
                    mediaMovieMapper.insert(movie);
                    return movie;
                },
                (movie, resetMatch) -> mediaMovieMapper.update(null, new LambdaUpdateWrapper<MediaMovie>()
                        .eq(MediaMovie::getId, movie.getId())
                        .set(MediaMovie::getDirectoryId, ctx.directory().getId())
                        .set(MediaMovie::getSourceId, ctx.source().getId())
                        .set(MediaMovie::getTitle, title)
                        .set(MediaMovie::getReleaseYear, releaseYear)
                        .set(MediaMovie::getScanTime, batchTime)
                        .set(resetMatch, MediaMovie::getMetadataId, null)
                        .set(resetMatch, MediaMovie::getMatchStatus, MediaMatchStatus.UNMATCHED.getCode())
                        .set(resetMatch, MediaMovie::getMetadataComplete, false)),
                MediaMovie::getId, MediaMovie::getTitle, MediaMovie::getReleaseYear,
                MediaMovie::getMatchStatus, MediaMovie::getMetadataId);
    }

    /**
     * 电影文件明细 reconcile：锚命中且未变化（哈希相同且归属电影一致）则跳过；
     * 否则按锚 upsert 或改挂（跨电影/跨库移动的文件行直接改挂到本电影）。
     * 锚全局唯一：文件跨电影移动时，锚命中的行可能属于其他电影，此时直接改挂到本电影，
     * 源电影被清空的明细由源电影删除阶段（{@link #deleteUnseenMovieFiles}）删除。
     * 循环骨架委托 {@link MediaReconcileDriverSupport#reconcileRow}。
     */
    private void reconcileMovieFile(MediaScanContext ctx, MediaMovie movie, FileNode file,
                                    MoviePrepare prepare, Set<String> seenFileRowIds) {
        mediaReconcileDriverSupport.reconcileRow(ctx, file, prepare.probeByFileId(),
                prepare.fileByNodeId(), prepare.existingFiles(), new RowReconciler<MediaMovieFile>() {
                    @Override
                    public String fileHash(MediaMovieFile row) {
                        return row.getFileHash();
                    }

                    @Override
                    public boolean hashHitExtraOk(MediaMovieFile row, MediaScanContext c, FileProbe fp) {
                        return movie.getId().equals(row.getMovieId());
                    }

                    @Override
                    public void markSeen(MediaMovieFile row) {
                        seenFileRowIds.add(row.getId());
                    }

                    @Override
                    public MediaMovieFile findByFileNodeId(String fileNodeId) {
                        return mediaMovieFileMapper.selectOne(new LambdaQueryWrapper<MediaMovieFile>()
                                .eq(MediaMovieFile::getFileNodeId, fileNodeId));
                    }

                    @Override
                    public MediaMovieFile newRow(FileNode f) {
                        MediaMovieFile row = new MediaMovieFile();
                        row.setFileNodeId(f.getId());
                        return row;
                    }

                    @Override
                    public void assignAndPersist(MediaMovieFile row, MediaScanContext c, FileNode f, FileProbe fp) {
                        row.setMovieId(movie.getId());
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
                            mediaMovieFileMapper.insert(row);
                        } else {
                            mediaMovieFileMapper.updateById(row);
                        }
                    }
                });
    }

    /**
     * 重算电影当前文件明细的最早入库时间。
     */
    private void recalcAddedTime(String movieId) {
        LocalDateTime min = mediaMovieFileMapper.selectList(new LambdaQueryWrapper<MediaMovieFile>()
                        .eq(MediaMovieFile::getMovieId, movieId)
                        .isNotNull(MediaMovieFile::getCreateTime)
                        .orderByAsc(MediaMovieFile::getCreateTime)
                        .last("limit 1"))
                .stream().map(MediaMovieFile::getCreateTime).filter(Objects::nonNull)
                .findFirst().orElse(null);
        mediaMovieMapper.update(null, new LambdaUpdateWrapper<MediaMovie>()
                .eq(MediaMovie::getId, movieId)
                .set(MediaMovie::getAddedTime, min));
    }
}
