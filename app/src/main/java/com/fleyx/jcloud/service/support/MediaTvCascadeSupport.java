package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaFavoriteOwnerType;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.service.MediaFavoriteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 电视库新模型级联删除支撑组件（ADR 0021 / issue #17）。
 * <p>
 * 票据 07 共享骨架重构后仅保留删除深度差异：四层「集文件→集→季→剧」级联（各级连带其 owner
 * 反向指针指向的 t_media_metadata 行，集文件明细的外部字幕记录一并删除）与收藏归属类型；
 * 锚文件来源内守卫、「选 ids→委派」骨架、未见行删除循环等公共部分委托
 * {@link MediaCascadeDriverSupport}。即时 reconcile 的删除、批次清理与媒体库删除共用同一套级联。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaTvCascadeSupport {

    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaEpisodeFileMapper mediaEpisodeFileMapper;
    private final MediaFavoriteService mediaFavoriteService;
    private final MediaCascadeDriverSupport mediaCascadeDriverSupport;
    private final MediaWatchedLinkageSupport mediaWatchedLinkageSupport;

    /**
     * 级联删除若干部剧：集文件 → 集 → 季 → 剧行，各级连带其 owner 指向的 t_media_metadata 行；
     * 集文件明细的外部字幕记录一并删除（issue #19）。
     *
     * @param seriesIds 剧 ID 集合
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSeriesCascade(Collection<String> seriesIds) {
        if (seriesIds == null || seriesIds.isEmpty()) {
            return;
        }
        for (String seriesId : seriesIds) {
            List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(
                    new LambdaQueryWrapper<MediaEpisode>().eq(MediaEpisode::getSeriesId, seriesId));
            List<String> episodeIds = new ArrayList<>();
            List<String> seasonIds = new ArrayList<>();
            if (!episodes.isEmpty()) {
                episodeIds = episodes.stream().map(MediaEpisode::getId).toList();
                List<MediaEpisodeFile> files = mediaEpisodeFileMapper.selectList(
                        new LambdaQueryWrapper<MediaEpisodeFile>().in(MediaEpisodeFile::getEpisodeId, episodeIds));
                mediaCascadeDriverSupport.deleteSubtitleByFileIds(
                        files.stream().map(MediaEpisodeFile::getId).toList());
                mediaEpisodeFileMapper.delete(new LambdaQueryWrapper<MediaEpisodeFile>()
                        .in(MediaEpisodeFile::getEpisodeId, episodeIds));
                for (String episodeId : episodeIds) {
                    mediaCascadeDriverSupport.deleteMetadata(MediaMetadataOwnerType.EPISODE.getCode(), episodeId);
                }
                mediaEpisodeMapper.deleteBatchIds(episodeIds);
            }
            List<MediaSeason> seasons = mediaSeasonMapper.selectList(
                    new LambdaQueryWrapper<MediaSeason>().eq(MediaSeason::getSeriesId, seriesId));
            for (MediaSeason season : seasons) {
                seasonIds.add(season.getId());
                mediaCascadeDriverSupport.deleteMetadata(MediaMetadataOwnerType.SEASON.getCode(), season.getId());
            }
            if (!seasons.isEmpty()) {
                mediaSeasonMapper.deleteBatchIds(seasons.stream().map(MediaSeason::getId).toList());
            }
            mediaCascadeDriverSupport.deleteMetadata(MediaMetadataOwnerType.SERIES.getCode(), seriesId);
            mediaSeriesMapper.deleteById(seriesId);
            // 实体删除后清理其收藏记录（不限用户）
            mediaFavoriteService.deleteByOwners(MediaFavoriteOwnerType.EPISODE, episodeIds);
            mediaFavoriteService.deleteByOwners(MediaFavoriteOwnerType.SEASON, seasonIds);
            mediaFavoriteService.deleteByOwners(MediaFavoriteOwnerType.SERIES, List.of(seriesId));
        }
        log.info("级联删除剧 {} 部: ids={}", seriesIds.size(), seriesIds);
    }

    /**
     * 删除媒体库下指定来源目录的全部剧（媒体库增删来源目录时调用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDirectoryAndSourceIds(String directoryId, Collection<String> sourceIds) {
        mediaCascadeDriverSupport.deleteByDirectoryAndSourceIds(directoryId, sourceIds,
                (dirId, srcIds) -> mediaSeriesMapper.selectList(new LambdaQueryWrapper<MediaSeries>()
                                .eq(MediaSeries::getDirectoryId, dirId)
                                .in(MediaSeries::getSourceId, srcIds)
                                .select(MediaSeries::getId))
                        .stream().map(MediaSeries::getId).toList(),
                this::deleteSeriesCascade);
    }

    /**
     * 删除媒体库下全部剧（媒体库删除时调用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDirectoryId(String directoryId) {
        mediaCascadeDriverSupport.deleteByDirectoryId(directoryId,
                dirId -> mediaSeriesMapper.selectList(new LambdaQueryWrapper<MediaSeries>()
                                .eq(MediaSeries::getDirectoryId, dirId)
                                .select(MediaSeries::getId))
                        .stream().map(MediaSeries::getId).toList(),
                this::deleteSeriesCascade);
    }

    /**
     * 即时删除亲眼确认消失的子项：未见的集文件明细、集、季（连带各自元数据行）。
     * <p>
     * 防跨剧/跨来源移动竞态：删除集文件明细前确认锚文件节点已不存在或已不在本库任何来源下；
     * 文件仍存在本库来源内时跳过删除（留给新父级 reconcile 按锚改挂，进度不丢失）。
     * 集/季仅在不再有任何子行引用时才删除。
     *
     * @param sourceFullIdPaths 本库全部可达来源目录的完整物化路径
     */
    public void deleteUnseenChildren(List<MediaSeason> existingSeasons, List<MediaEpisode> existingEpisodes,
                                     List<MediaEpisodeFile> existingFiles, Set<String> seenSeasonIds,
                                     Set<String> seenEpisodeIds, Set<String> seenFileRowIds,
                                     List<String> sourceFullIdPaths) {
        mediaCascadeDriverSupport.deleteUnseenRows(existingFiles, seenFileRowIds, sourceFullIdPaths,
                MediaEpisodeFile::getId, MediaEpisodeFile::getFileNodeId,
                mediaEpisodeFileMapper::deleteBatchIds, null, null);
        List<String> removedEpisodeIds = new ArrayList<>();
        for (MediaEpisode episode : existingEpisodes) {
            if (!seenEpisodeIds.contains(episode.getId())) {
                removedEpisodeIds.add(episode.getId());
            }
        }
        List<String> deletedEpisodeIds = new ArrayList<>();
        for (String episodeId : removedEpisodeIds) {
            Long remaining = mediaEpisodeFileMapper.selectCount(new LambdaQueryWrapper<MediaEpisodeFile>()
                    .eq(MediaEpisodeFile::getEpisodeId, episodeId));
            if (remaining != null && remaining > 0) {
                continue;
            }
            MediaEpisode episode = existingEpisodes.stream()
                    .filter(e -> e.getId().equals(episodeId)).findFirst().orElse(null);
            mediaCascadeDriverSupport.deleteMetadata(MediaMetadataOwnerType.EPISODE.getCode(), episodeId);
            mediaEpisodeMapper.deleteById(episodeId);
            deletedEpisodeIds.add(episodeId);
            // 集删除后重算其父级标记（工单 02）
            if (episode != null) {
                mediaWatchedLinkageSupport.recomputeParents(episode.getSeasonId(), episode.getSeriesId());
            }
            log.info("即时删除消失的集: {}", episodeId);
        }
        mediaFavoriteService.deleteByOwners(MediaFavoriteOwnerType.EPISODE, deletedEpisodeIds);
        List<String> removedSeasonIds = new ArrayList<>();
        for (MediaSeason season : existingSeasons) {
            if (!seenSeasonIds.contains(season.getId())) {
                removedSeasonIds.add(season.getId());
            }
        }
        List<String> deletedSeasonIds = new ArrayList<>();
        for (String seasonId : removedSeasonIds) {
            Long remaining = mediaEpisodeMapper.selectCount(new LambdaQueryWrapper<MediaEpisode>()
                    .eq(MediaEpisode::getSeasonId, seasonId));
            if (remaining != null && remaining > 0) {
                continue;
            }
            MediaSeason season = existingSeasons.stream()
                    .filter(s -> s.getId().equals(seasonId)).findFirst().orElse(null);
            mediaCascadeDriverSupport.deleteMetadata(MediaMetadataOwnerType.SEASON.getCode(), seasonId);
            mediaSeasonMapper.deleteById(seasonId);
            deletedSeasonIds.add(seasonId);
            // 季删除后重算所属剧标记（工单 02）
            if (season != null) {
                mediaWatchedLinkageSupport.recomputeSeries(season.getSeriesId());
            }
            log.info("即时删除消失的季: {}", seasonId);
        }
        mediaFavoriteService.deleteByOwners(MediaFavoriteOwnerType.SEASON, deletedSeasonIds);
    }
}
