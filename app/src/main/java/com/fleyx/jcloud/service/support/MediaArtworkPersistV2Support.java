package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.common.enums.MediaPersistStatus;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 新模型元数据写回支撑组件（ADR 0020 / issue #20）：削刮成功后把 NFO 与图片写回视频所在目录。
 * <p>
 * 写回为正式 FileNode（复用 {@link MediaArtworkPersistSupport} 的原语）：本地来源写物理文件 + 插入/覆盖
 * FileNode；远程来源经远程上传通道落盘。local_nfo 来源同样执行写回（ADR 0023）：合并补全后的元数据
 * 整体重写 NFO（{@code writeNfoXml} 覆盖，用户自定义未知字段不保留，PRD 已确认接受），图片走
 * ensureArtworkIfMissing——已存在的本地图片文件直接沿用不覆盖，缺失的按 rawJson 下载。
 * 任一步失败仅将对应元数据标记 persist_status=failed，不影响削刮主流程。
 * 电影写 {@code movie.nfo} + folder.jpg/backdrop.jpg（ADR 0022）；剧写
 * tvshow.nfo + folder.jpg/backdrop.jpg + 季海报（seasonXX-poster.jpg）+ 逐集 nfo 与剧照（{@code <视频名>-thumb.jpg}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaArtworkPersistV2Support {

    private final FileMapper fileMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaEpisodeFileMapper mediaEpisodeFileMapper;
    private final MediaPlaybackResolveSupport mediaPlaybackResolveSupport;
    private final MediaNfoSupport nfoSupport;
    private final MediaArtworkPersistSupport persistSupport;

    /**
     * 写回电影级元数据：{@code movie.nfo} + folder.jpg/backdrop.jpg 到电影文件夹
     * （local_nfo 来源同样整体写回，ADR 0023）。任一步失败仅标记 failed 不影响削刮结果。
     */
    public void persistMovieV2(MediaMovie movie, MediaMetadata metadata) {
        try {
            FileNode folder = fileMapper.selectById(movie.getFolderNodeId());
            if (folder == null) {
                throw new IllegalStateException("电影文件夹节点不存在: " + movie.getFolderNodeId());
            }
            MediaMovieFile file = mediaPlaybackResolveSupport.pickMovieFile(movie);
            FileNode video = file == null ? null : fileMapper.selectById(file.getFileNodeId());
            if (video == null) {
                throw new IllegalStateException("电影视频文件节点不存在: " + movie.getId());
            }
            persistSupport.writeNfoXml(folder, MediaNfoSupport.MOVIE_NFO,
                    nfoSupport.generate(metadata, null, null));
            FileNode poster = ensureArtworkIfMissing(metadata.getPosterFileNodeId(), folder,
                    MediaNfoSupport.POSTER_WRITE_NAME, metadata.getRawJson(), "poster_path", "poster");
            FileNode fanart = ensureArtworkIfMissing(metadata.getBackdropFileNodeId(), folder,
                    MediaNfoSupport.BACKDROP_WRITE_NAME, metadata.getRawJson(), "backdrop_path", "backdrop");
            if (poster != null) {
                metadata.setPosterFileNodeId(poster.getId());
            }
            if (fanart != null) {
                metadata.setBackdropFileNodeId(fanart.getId());
            }
            metadata.setPersistStatus(MediaPersistStatus.PERSISTED.getCode());
        } catch (Exception e) {
            log.warn("电影元数据写回失败: movie={}, error={}", movie.getId(), e.getMessage());
            metadata.setPersistStatus(MediaPersistStatus.FAILED.getCode());
        } finally {
            mediaMetadataMapper.updateById(metadata);
        }
    }

    /**
     * 写回剧级元数据：tvshow.nfo + folder.jpg/backdrop.jpg（剧文件夹）、季海报、逐集 nfo 与剧照
     * （local_nfo 来源同样执行写回，ADR 0023）。各级别独立成败：剧级失败不影响季/集继续写回。
     */
    public void persistSeriesV2(MediaSeries series, MediaMetadata seriesMetadata) {
        FileNode seriesFolder = null;
        boolean ok = true;
        try {
            seriesFolder = fileMapper.selectById(series.getFolderNodeId());
            if (seriesFolder == null) {
                throw new IllegalStateException("剧文件夹节点不存在: " + series.getFolderNodeId());
            }
            persistSupport.writeNfoXml(seriesFolder, MediaNfoSupport.TVSHOW_NFO,
                    nfoSupport.generate(seriesMetadata, null, null));
            FileNode poster = ensureArtworkIfMissing(seriesMetadata.getPosterFileNodeId(), seriesFolder,
                    MediaNfoSupport.POSTER_WRITE_NAME, seriesMetadata.getRawJson(), "poster_path", "poster");
            FileNode fanart = ensureArtworkIfMissing(seriesMetadata.getBackdropFileNodeId(), seriesFolder,
                    MediaNfoSupport.BACKDROP_WRITE_NAME, seriesMetadata.getRawJson(), "backdrop_path", "backdrop");
            if (poster != null) {
                seriesMetadata.setPosterFileNodeId(poster.getId());
            }
            if (fanart != null) {
                seriesMetadata.setBackdropFileNodeId(fanart.getId());
            }
        } catch (Exception e) {
            ok = false;
            log.warn("剧元数据写回失败: series={}, error={}", series.getId(), e.getMessage());
        }
        seriesMetadata.setPersistStatus(ok ? MediaPersistStatus.PERSISTED.getCode() : MediaPersistStatus.FAILED.getCode());
        mediaMetadataMapper.updateById(seriesMetadata);
        if (seriesFolder == null) {
            return;
        }
        persistSeasonPosters(series, seriesFolder);
        persistEpisodeNfos(series);
    }

    private void persistSeasonPosters(MediaSeries series, FileNode seriesFolder) {
        List<MediaSeason> seasons = mediaSeasonMapper.selectList(
                new LambdaQueryWrapper<MediaSeason>().eq(MediaSeason::getSeriesId, series.getId()));
        for (MediaSeason season : seasons) {
            if (season.getSeasonNo() == null || season.getMetadataId() == null) {
                continue;
            }
            MediaMetadata metadata = mediaMetadataMapper.selectById(season.getMetadataId());
            if (metadata == null) {
                continue;
            }
            try {
                FileNode poster = ensureArtworkIfMissing(metadata.getPosterFileNodeId(), seriesFolder,
                        nfoSupport.seasonPosterName(season.getSeasonNo()),
                        metadata.getRawJson(), "poster_path", "poster");
                if (poster != null) {
                    metadata.setPosterFileNodeId(poster.getId());
                }
                metadata.setPersistStatus(MediaPersistStatus.PERSISTED.getCode());
            } catch (Exception e) {
                log.warn("季海报写回失败: season={}, error={}", season.getId(), e.getMessage());
                metadata.setPersistStatus(MediaPersistStatus.FAILED.getCode());
            }
            mediaMetadataMapper.updateById(metadata);
        }
    }

    private void persistEpisodeNfos(MediaSeries series) {
        List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                .eq(MediaEpisode::getSeriesId, series.getId())
                .isNotNull(MediaEpisode::getMetadataId));
        for (MediaEpisode episode : episodes) {
            MediaMetadata metadata = mediaMetadataMapper.selectById(episode.getMetadataId());
            if (metadata == null || !MediaMetadataOwnerType.EPISODE.getCode().equals(metadata.getOwnerType())) {
                continue;
            }
            try {
                MediaEpisodeFile file = mediaPlaybackResolveSupport.pickEpisodeFile(episode);
                FileNode video = file == null ? null : fileMapper.selectById(file.getFileNodeId());
                FileNode dir = video == null ? null : fileMapper.selectById(video.getParentId());
                if (video == null || dir == null) {
                    throw new IllegalStateException("集视频文件节点不存在: " + episode.getId());
                }
                persistSupport.writeNfoXml(dir, nfoSupport.nfoNameOf(video.getName()),
                        nfoSupport.generate(metadata, episodeSeasonNo(episode), episode.getEpisodeNo()));
                FileNode thumb = ensureArtworkIfMissing(metadata.getPosterFileNodeId(), dir,
                        nfoSupport.episodeThumbNameOf(video.getName()),
                        metadata.getRawJson(), "still_path", "poster");
                if (thumb == null) {
                    thumb = ensureArtworkIfMissing(metadata.getPosterFileNodeId(), dir,
                            nfoSupport.episodeThumbNameOf(video.getName()),
                            metadata.getRawJson(), "poster_path", "poster");
                }
                if (thumb != null) {
                    metadata.setPosterFileNodeId(thumb.getId());
                }
                metadata.setPersistStatus(MediaPersistStatus.PERSISTED.getCode());
            } catch (Exception e) {
                log.warn("集元数据写回失败: episode={}, error={}", episode.getId(), e.getMessage());
                metadata.setPersistStatus(MediaPersistStatus.FAILED.getCode());
            }
            mediaMetadataMapper.updateById(metadata);
        }
    }

    private Integer episodeSeasonNo(MediaEpisode episode) {
        MediaSeason season = mediaSeasonMapper.selectById(episode.getSeasonId());
        return season == null ? null : season.getSeasonNo();
    }

    /**
     * 确保图片产物存在：元数据行已绑定且文件节点存活的图片直接沿用（本地优先，ADR 0023——本地识别链图片
     * 如 poster.jpg 不被写回名 folder.jpg 覆盖），否则走 IfMissing 语义下载写回（缺失才下载）。
     *
     * @param boundNodeId 元数据行已绑定的图片节点 ID（posterFileNodeId/backdropFileNodeId），可为空
     * @param dir         目标目录
     * @param writeName   写回文件名（未绑定时按此名下载）
     * @param rawJson     元数据原始响应（含 TMDB 图片路径）
     * @param jsonField   图片路径字段（poster_path/backdrop_path/still_path）
     * @param kind        图片用途（poster/backdrop/still）
     */
    private FileNode ensureArtworkIfMissing(String boundNodeId, FileNode dir, String writeName,
                                            String rawJson, String jsonField, String kind) {
        FileNode bound = boundNodeId == null ? null : fileMapper.selectById(boundNodeId);
        if (bound != null) {
            return bound;
        }
        return persistSupport.ensureArtworkIfMissing(dir, writeName, rawJson, jsonField, kind);
    }
}
