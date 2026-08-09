package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaFavoriteOwnerType;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.service.MediaFavoriteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 电影库新模型级联删除支撑组件（ADR 0021 / issue #18）。
 * <p>
 * 票据 07 共享骨架重构后仅保留删除深度差异：两层「电影文件明细 → 电影行」级联（连带其 owner
 * 反向指针指向的 t_media_metadata 行，明细的外部字幕记录一并删除）与收藏归属类型；
 * 锚文件来源内守卫、「选 ids→委派」骨架、未见行删除循环等公共部分委托
 * {@link MediaCascadeDriverSupport}。即时 reconcile 的删除、批次清理与媒体库删除共用同一套级联。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaMovieCascadeSupport {

    private final MediaMovieMapper mediaMovieMapper;
    private final MediaMovieFileMapper mediaMovieFileMapper;
    private final MediaFavoriteService mediaFavoriteService;
    private final MediaCascadeDriverSupport mediaCascadeDriverSupport;

    /**
     * 级联删除若干部电影：电影文件明细 → 电影行，各级连带其 owner 指向的 t_media_metadata 行；
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
                mediaCascadeDriverSupport.deleteSubtitleByFileIds(
                        files.stream().map(MediaMovieFile::getId).toList());
                mediaMovieFileMapper.deleteBatchIds(files.stream().map(MediaMovieFile::getId).toList());
            }
            mediaCascadeDriverSupport.deleteMetadata(MediaMetadataOwnerType.MOVIE.getCode(), movieId);
            mediaMovieMapper.deleteById(movieId);
        }
        // 实体删除后清理其收藏记录（不限用户）
        mediaFavoriteService.deleteByOwners(MediaFavoriteOwnerType.MOVIE, movieIds);
        log.info("级联删除电影 {} 部: ids={}", movieIds.size(), movieIds);
    }

    /**
     * 删除媒体库下指定来源目录的全部电影（媒体库增删来源目录时调用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDirectoryAndSourceIds(String directoryId, Collection<String> sourceIds) {
        mediaCascadeDriverSupport.deleteByDirectoryAndSourceIds(directoryId, sourceIds,
                (dirId, srcIds) -> mediaMovieMapper.selectList(new LambdaQueryWrapper<MediaMovie>()
                                .eq(MediaMovie::getDirectoryId, dirId)
                                .in(MediaMovie::getSourceId, srcIds)
                                .select(MediaMovie::getId))
                        .stream().map(MediaMovie::getId).toList(),
                this::deleteMoviesCascade);
    }

    /**
     * 删除媒体库下全部电影（媒体库删除时调用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDirectoryId(String directoryId) {
        mediaCascadeDriverSupport.deleteByDirectoryId(directoryId,
                dirId -> mediaMovieMapper.selectList(new LambdaQueryWrapper<MediaMovie>()
                                .eq(MediaMovie::getDirectoryId, dirId)
                                .select(MediaMovie::getId))
                        .stream().map(MediaMovie::getId).toList(),
                this::deleteMoviesCascade);
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
        mediaCascadeDriverSupport.deleteUnseenRows(existingFiles, seenFileRowIds, sourceFullIdPaths,
                MediaMovieFile::getId, MediaMovieFile::getFileNodeId,
                mediaMovieFileMapper::deleteBatchIds, null, "即时删除消失的电影文件明细");
    }
}
