package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.service.TmdbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 电视库新模型削刮支撑组件（ADR 0021 / issue #20）。
 * <p>
 * 剧集以剧为单位削刮：一次匹配应用到全剧。本地优先：剧文件夹存在 tvshow.nfo 或本地媒体图片
 * （poster/fanart/seasonXX-poster.jpg）时完全信任本地内容、不请求 TMDB 补全；本地缺失时才走
 * TMDB 匹配，季/集元数据按剧级匹配派生——本地实际存在的季逐个拉取整季数据（含季内全部集）。
 * 每次削刮结束后重算剧集行的元数据完整性（聚合语义，见 {@link MediaMetadataCompleteSupport}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaTvScrapeSupport {

    private final FileMapper fileMapper;
    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaMetadataSupport metadataV2Support;
    private final MediaMetadataCompleteSupport completeSupport;
    private final MediaArtworkPersistV2Support artworkPersistV2Support;
    private final MediaArtworkPersistSupport persistSupport;
    private final MediaNfoSupport nfoSupport;
    private final MediaPlaybackResolveSupport playbackResolveSupport;
    private final TmdbService tmdbService;

    /**
     * 电视剧本地优先削刮：解析 tvshow.nfo 绑定剧元数据，绑定季海报，逐集解析集 NFO 绑定集元数据；
     * 无 tvshow.nfo 但存在本地图片时同样本地优先，构建缺失文本字段的 local_nfo 剧元数据（标记不完整）。
     *
     * @return 剧元数据（已绑定 owner）；无 tvshow.nfo 且无本地图片时返回 null（调用方回退 TMDB 流程）
     */
    public MediaMetadata scrapeSeriesLocalNfo(MediaSeries series) {
        FileNode seriesFolder = fileMapper.selectById(series.getFolderNodeId());
        if (seriesFolder == null) {
            return null;
        }
        String userId = series.getUserId();
        FileNode nfoNode = persistSupport.findChildFile(userId, seriesFolder.getId(), MediaNfoSupport.TVSHOW_NFO);
        MediaNfoSupport.NfoData data = nfoNode == null ? null : readNfo(nfoNode);
        FileNode poster = persistSupport.findChildFile(userId, seriesFolder.getId(), MediaNfoSupport.POSTER_JPG);
        FileNode fanart = persistSupport.findChildFile(userId, seriesFolder.getId(), MediaNfoSupport.FANART_JPG);
        if (data == null && poster == null && fanart == null && !hasSeasonPoster(series, userId, seriesFolder)) {
            return null;
        }
        MediaMetadata seriesMetadata = metadataV2Support.upsertLocal(
                MediaMetadataOwnerType.SERIES.getCode(), series.getId(), userId,
                data == null ? MediaNfoSupport.emptyData("tv") : data,
                poster == null ? null : poster.getId(), fanart == null ? null : fanart.getId());
        applySeasonPosters(series, userId, seriesFolder);
        applyEpisodeNfos(series, userId);
        return seriesMetadata;
    }

    /**
     * 应用本地优先削刮结果：绑定剧行（matched）→ 写回（local 跳过写回、标记 persisted）→ 重算完整性。
     */
    public void applyLocalSeriesMatch(MediaSeries series, MediaMetadata localMetadata) {
        MediaMetadata bound = metadataV2Support.upsertByOwner(
                MediaMetadataOwnerType.SERIES.getCode(), series.getId(), localMetadata);
        bindSeriesRow(series, bound, MediaMatchStatus.MATCHED.getCode());
        artworkPersistV2Support.persistSeriesV2(series, bound);
        completeSupport.refreshSeriesComplete(series);
    }

    /**
     * 应用剧级匹配（TMDB 自动匹配/手动修正共用）：绑定剧行 → 派生季/集元数据 → 写回 → 重算完整性。
     *
     * @param series      剧行
     * @param detached    未绑定 owner 的剧元数据（TMDB 拉取结果）
     * @param matchStatus 剧行匹配状态（matched / manual）
     */
    public void applySeriesMatchWithDerivation(MediaSeries series, MediaMetadata detached, String matchStatus) {
        MediaMetadata bound = metadataV2Support.upsertByOwner(
                MediaMetadataOwnerType.SERIES.getCode(), series.getId(), detached);
        bindSeriesRow(series, bound, matchStatus);
        deriveSeasonEpisodes(series, bound);
        artworkPersistV2Support.persistSeriesV2(series, bound);
        completeSupport.refreshSeriesComplete(series);
    }

    /**
     * 应用剧级匹配失败：清空剧/季/集全部元数据行与关联（owner 指针不留孤儿），置未匹配并重算完整性。
     */
    public void applySeriesUnmatch(MediaSeries series) {
        List<MediaSeason> seasons = mediaSeasonMapper.selectList(
                new LambdaQueryWrapper<MediaSeason>().eq(MediaSeason::getSeriesId, series.getId()));
        for (MediaSeason season : seasons) {
            clearOwner(season.getId(), season.getMetadataId(), season);
        }
        List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(
                new LambdaQueryWrapper<MediaEpisode>().eq(MediaEpisode::getSeriesId, series.getId()));
        for (MediaEpisode episode : episodes) {
            metadataV2Support.deleteByOwner(MediaMetadataOwnerType.EPISODE.getCode(), episode.getId());
            if (episode.getMetadataId() != null) {
                MediaEpisode update = new MediaEpisode();
                update.setId(episode.getId());
                update.setMetadataId(null);
                mediaEpisodeMapper.updateById(update);
            }
        }
        metadataV2Support.deleteByOwner(MediaMetadataOwnerType.SERIES.getCode(), series.getId());
        bindSeriesRow(series, null, MediaMatchStatus.UNMATCHED.getCode());
        completeSupport.refreshSeriesComplete(series);
    }

    /**
     * 按剧级匹配派生季/集元数据：本地实际存在的季逐个拉取整季数据（含季内全部集），
     * 季/集元数据 upsert 到各自 owner 并回写 metadata_id；单个季拉取失败仅记日志不影响其他季。
     * 供自动削刮与剧级手动修正共用。
     */
    public void deriveSeasonEpisodes(MediaSeries series, MediaMetadata seriesMetadata) {
        if (seriesMetadata.getTmdbId() == null) {
            return;
        }
        List<MediaSeason> seasons = mediaSeasonMapper.selectList(
                new LambdaQueryWrapper<MediaSeason>().eq(MediaSeason::getSeriesId, series.getId()));
        for (MediaSeason season : seasons) {
            if (season.getSeasonNo() == null) {
                continue;
            }
            try {
                TmdbService.SeasonFetchV2 result = tmdbService.fetchSeasonV2(
                        series.getUserId(), seriesMetadata.getTmdbId(), season.getSeasonNo());
                if (result == null || result.season() == null) {
                    continue;
                }
                MediaMetadata seasonBound = metadataV2Support.upsertByOwner(
                        MediaMetadataOwnerType.SEASON.getCode(), season.getId(), result.season());
                bindSeasonRow(season, seasonBound);
                List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(
                        new LambdaQueryWrapper<MediaEpisode>().eq(MediaEpisode::getSeasonId, season.getId()));
                for (MediaEpisode episode : episodes) {
                    MediaMetadata episodeMeta = result.episodes().get(episode.getEpisodeNo());
                    if (episodeMeta == null) {
                        continue;
                    }
                    MediaMetadata episodeBound = metadataV2Support.upsertByOwner(
                            MediaMetadataOwnerType.EPISODE.getCode(), episode.getId(), episodeMeta);
                    MediaEpisode update = new MediaEpisode();
                    update.setId(episode.getId());
                    update.setMetadataId(episodeBound.getId());
                    mediaEpisodeMapper.updateById(update);
                }
            } catch (Exception e) {
                log.warn("季元数据拉取失败: series={}, season={}, error={}",
                        series.getSeriesName(), season.getSeasonNo(), e.getMessage());
            }
        }
    }

    /**
     * 剧文件夹下是否存在任一季的季海报（seasonXX-poster.jpg）。
     */
    private boolean hasSeasonPoster(MediaSeries series, String userId, FileNode seriesFolder) {
        List<MediaSeason> seasons = mediaSeasonMapper.selectList(
                new LambdaQueryWrapper<MediaSeason>().eq(MediaSeason::getSeriesId, series.getId()));
        for (MediaSeason season : seasons) {
            if (season.getSeasonNo() == null) {
                continue;
            }
            FileNode poster = persistSupport.findChildFile(userId, seriesFolder.getId(),
                    nfoSupport.seasonPosterName(season.getSeasonNo()));
            if (poster != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 季海报绑定：剧文件夹下 seasonXX-poster.jpg 绑定到季元数据（无则新建 local_nfo 行，标记不完整）。
     */
    private void applySeasonPosters(MediaSeries series, String userId, FileNode seriesFolder) {
        List<MediaSeason> seasons = mediaSeasonMapper.selectList(
                new LambdaQueryWrapper<MediaSeason>().eq(MediaSeason::getSeriesId, series.getId()));
        for (MediaSeason season : seasons) {
            if (season.getSeasonNo() == null) {
                continue;
            }
            FileNode poster = persistSupport.findChildFile(userId, seriesFolder.getId(),
                    nfoSupport.seasonPosterName(season.getSeasonNo()));
            if (poster == null) {
                continue;
            }
            MediaMetadata seasonMetadata = metadataV2Support.upsertLocal(
                    MediaMetadataOwnerType.SEASON.getCode(), season.getId(), userId,
                    MediaNfoSupport.emptyData("season"), poster.getId(), null);
            bindSeasonRow(season, seasonMetadata);
        }
    }

    /**
     * 逐集解析集 NFO 绑定集元数据；无集 NFO 的集不绑定（完整性聚合时视为不完整）。
     */
    private void applyEpisodeNfos(MediaSeries series, String userId) {
        List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(
                new LambdaQueryWrapper<MediaEpisode>().eq(MediaEpisode::getSeriesId, series.getId()));
        for (MediaEpisode episode : episodes) {
            MediaEpisodeFile file = playbackResolveSupport.pickEpisodeFile(episode);
            FileNode video = file == null ? null : fileMapper.selectById(file.getFileNodeId());
            FileNode dir = video == null ? null : fileMapper.selectById(video.getParentId());
            if (video == null || dir == null) {
                continue;
            }
            FileNode nfoNode = persistSupport.findChildFile(userId, dir.getId(),
                    nfoSupport.nfoNameOf(video.getName()));
            MediaNfoSupport.NfoData data = nfoNode == null ? null : readNfo(nfoNode);
            if (data == null) {
                continue;
            }
            FileNode thumb = persistSupport.findChildFile(userId, dir.getId(),
                    nfoSupport.episodeThumbNameOf(video.getName()));
            MediaMetadata episodeMetadata = metadataV2Support.upsertLocal(
                    MediaMetadataOwnerType.EPISODE.getCode(), episode.getId(), userId, data,
                    thumb == null ? null : thumb.getId(), null);
            MediaEpisode update = new MediaEpisode();
            update.setId(episode.getId());
            update.setMetadataId(episodeMetadata.getId());
            mediaEpisodeMapper.updateById(update);
        }
    }

    private void bindSeasonRow(MediaSeason season, MediaMetadata metadata) {
        MediaSeason update = new MediaSeason();
        update.setId(season.getId());
        update.setMetadataId(metadata.getId());
        mediaSeasonMapper.updateById(update);
        season.setMetadataId(metadata.getId());
    }

    private void bindSeriesRow(MediaSeries series, MediaMetadata metadata, String matchStatus) {
        mediaSeriesMapper.update(null, new LambdaUpdateWrapper<MediaSeries>()
                .eq(MediaSeries::getId, series.getId())
                .set(MediaSeries::getMetadataId, metadata == null ? null : metadata.getId())
                .set(MediaSeries::getMatchStatus, matchStatus));
        series.setMetadataId(metadata == null ? null : metadata.getId());
        series.setMatchStatus(matchStatus);
    }

    private void clearOwner(String seasonId, String metadataId, MediaSeason season) {
        metadataV2Support.deleteByOwner(MediaMetadataOwnerType.SEASON.getCode(), seasonId);
        if (metadataId != null) {
            MediaSeason update = new MediaSeason();
            update.setId(seasonId);
            update.setMetadataId(null);
            mediaSeasonMapper.updateById(update);
            season.setMetadataId(null);
        }
    }

    private MediaNfoSupport.NfoData readNfo(FileNode nfoNode) {
        byte[] bytes = persistSupport.readFileBytes(nfoNode);
        return bytes == null ? null : nfoSupport.parse(new String(bytes, StandardCharsets.UTF_8));
    }
}
