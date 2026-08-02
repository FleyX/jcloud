package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaMetadataV2Mapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaMetadataV2;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 电影库新模型级联删除支撑组件（ADR 0021 / issue #18）。
 * <p>
 * 应用层事务内执行（项目禁用外键）：删电影 → 电影文件明细 → 电影行，
 * 连带其 owner 反向指针指向的 t_media_metadata_v2 行。
 * 即时 reconcile 的删除、批次清理与媒体库删除共用同一套级联。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaMovieCascadeSupport {

    private final MediaMovieMapper mediaMovieMapper;
    private final MediaMovieFileMapper mediaMovieFileMapper;
    private final MediaMetadataV2Mapper mediaMetadataV2Mapper;
    private final MediaSubtitleSupport mediaSubtitleSupport;
    private final FileMapper fileMapper;

    /**
     * 级联删除若干部电影：电影文件明细 → 电影行，各级连带其 owner 指向的 t_media_metadata_v2 行；
     * 电影文件明细的外部字幕记录一并删除（issue #19）。
     *
     * @param movieIds 电影 ID 集合
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteMoviesCascade(Collection<String> movieIds) {
        if (movieIds == null || movieIds.isEmpty()) {
            return;
        }
        for (String movieId : movieIds) {
            List<MediaMovieFile> files = mediaMovieFileMapper.selectList(
                    new LambdaQueryWrapper<MediaMovieFile>().eq(MediaMovieFile::getMovieId, movieId));
            if (!files.isEmpty()) {
                mediaSubtitleSupport.deleteByFileIds(files.stream().map(MediaMovieFile::getId).toList());
                mediaMovieFileMapper.deleteBatchIds(files.stream().map(MediaMovieFile::getId).toList());
            }
            deleteMetadata(MediaMetadataOwnerType.MOVIE.getCode(), movieId);
            mediaMovieMapper.deleteById(movieId);
        }
        log.info("级联删除电影 {} 部: ids={}", movieIds.size(), movieIds);
    }

    /**
     * 删除媒体库下指定来源目录的全部电影（媒体库增删来源目录时调用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDirectoryAndSourceIds(String directoryId, Collection<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return;
        }
        List<String> movieIds = mediaMovieMapper.selectList(new LambdaQueryWrapper<MediaMovie>()
                        .eq(MediaMovie::getDirectoryId, directoryId)
                        .in(MediaMovie::getSourceId, sourceIds)
                        .select(MediaMovie::getId))
                .stream().map(MediaMovie::getId).toList();
        deleteMoviesCascade(movieIds);
    }

    /**
     * 删除媒体库下全部电影（媒体库删除时调用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDirectoryId(String directoryId) {
        List<String> movieIds = mediaMovieMapper.selectList(new LambdaQueryWrapper<MediaMovie>()
                        .eq(MediaMovie::getDirectoryId, directoryId)
                        .select(MediaMovie::getId))
                .stream().map(MediaMovie::getId).toList();
        deleteMoviesCascade(movieIds);
    }

    /**
     * 即时删除亲眼确认消失的电影文件明细（视频文件被删除或移出本库来源）。
     * <p>
     * 防跨电影/跨来源移动竞态：删除明细前确认锚文件节点已不存在或已不在本库任何来源下；
     * 文件仍存在本库来源内时跳过删除（留给新父级 reconcile 按锚改挂，进度不丢失）。
     *
     * @param sourceFullIdPaths 本库全部可达来源目录的完整物化路径
     */
    public void deleteUnseenFiles(List<MediaMovieFile> existingFiles, Set<String> seenFileRowIds,
                                  List<String> sourceFullIdPaths) {
        List<String> removedFileIds = new ArrayList<>();
        for (MediaMovieFile file : existingFiles) {
            if (file.getId() == null || seenFileRowIds.contains(file.getId())) {
                continue;
            }
            if (anchoredNodeInSources(file.getFileNodeId(), sourceFullIdPaths)) {
                log.debug("锚文件已移至本库其他位置，明细行留给新父级改挂: {}", file.getFileNodeId());
                continue;
            }
            removedFileIds.add(file.getId());
        }
        if (!removedFileIds.isEmpty()) {
            mediaSubtitleSupport.deleteByFileIds(removedFileIds);
            mediaMovieFileMapper.deleteBatchIds(removedFileIds);
            log.info("即时删除消失的电影文件明细 {} 条: ids={}", removedFileIds.size(), removedFileIds);
        }
    }

    /**
     * 锚文件节点仍存在且仍位于本库任一来源目录子树内。
     */
    private boolean anchoredNodeInSources(String fileNodeId, List<String> sourceFullIdPaths) {
        FileNode node = fileMapper.selectById(fileNodeId);
        if (node == null || node.getPath() == null) {
            return false;
        }
        for (String sourcePath : sourceFullIdPaths) {
            if (node.getPath().startsWith(sourcePath + ".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 删除实体一对一绑定的元数据行（owner 反向指针定位），无对应行时无事发生。
     *
     * @param ownerType 归属实体类型（{@link MediaMetadataOwnerType}）
     * @param ownerId   归属实体 ID
     */
    public void deleteMetadata(String ownerType, String ownerId) {
        mediaMetadataV2Mapper.delete(new LambdaQueryWrapper<MediaMetadataV2>()
                .eq(MediaMetadataV2::getOwnerType, ownerType)
                .eq(MediaMetadataV2::getOwnerId, ownerId));
    }
}
