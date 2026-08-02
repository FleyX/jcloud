package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaCompleteStatus;
import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaMetadataSource;
import com.fleyx.jcloud.common.enums.MediaPersistStatus;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 电视剧本地优先削刮支撑组件（ADR 0020）：剧文件夹存在 tvshow.nfo 或本地媒体图片时完全信任本地内容，
 * 不请求 TMDB 补全；字段残缺标记为不完整。处理剧/季/集三级元数据绑定，并在成功后写回规范化 NFO。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaLocalNfoScrapeSupport {

    private final FileMapper fileMapper;
    private final MediaItemMapper mediaItemMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final MediaNfoSupport nfoSupport;
    private final MediaArtworkPersistSupport persistSupport;

    /**
     * 电视剧本地优先削刮：解析 tvshow.nfo 绑定剧元数据，绑定季海报，逐集解析集 NFO 绑定集元数据。
     * 无 tvshow.nfo 但剧文件夹存在本地图片（poster.jpg/fanart.jpg/seasonXX-poster.jpg）时同样本地优先，
     * 构建缺失文本字段的 local_nfo 剧元数据（标记不完整），图片绑定本地文件。
     *
     * @param series 剧记录
     * @return 剧元数据；无 tvshow.nfo 且无本地图片时返回 null（调用方回退 TMDB 流程）
     */
    public MediaMetadata scrapeSeriesLocalNfo(MediaSeries series) {
        FileNode seriesFolder = persistSupport.resolveSeriesFolder(series);
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
        MediaMetadata seriesMetadata = nfoSupport.upsertLocalMetadata(series.getMetadataId(), userId, "tv",
                data == null ? MediaNfoSupport.emptyData("tv") : data,
                poster == null ? null : poster.getId(), fanart == null ? null : fanart.getId());
        applySeasonPosters(series, userId, seriesFolder);
        applyEpisodeNfos(series, userId, seriesMetadata);
        persistSupport.persistSeries(series, seriesMetadata);
        return seriesMetadata;
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
     * 季海报绑定：剧文件夹下 seasonXX-poster.jpg 绑定到季元数据（无则新建 local_nfo 行）。
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
            MediaMetadata seasonMetadata = season.getMetadataId() == null ? null
                    : mediaMetadataMapper.selectById(season.getMetadataId());
            if (seasonMetadata != null && !MediaMetadataSource.LOCAL_NFO.getCode().equals(seasonMetadata.getSource())) {
                seasonMetadata = null;
            }
            if (seasonMetadata == null && poster == null) {
                continue;
            }
            if (seasonMetadata == null) {
                seasonMetadata = new MediaMetadata();
                seasonMetadata.setUserId(userId);
                seasonMetadata.setMediaType("season");
                seasonMetadata.setSource(MediaMetadataSource.LOCAL_NFO.getCode());
                seasonMetadata.setCompleteStatus(MediaCompleteStatus.INCOMPLETE.getCode());
                seasonMetadata.setPersistStatus(MediaPersistStatus.PENDING.getCode());
                seasonMetadata.setPosterFileNodeId(poster.getId());
                mediaMetadataMapper.insert(seasonMetadata);
                MediaSeason update = new MediaSeason();
                update.setId(season.getId());
                update.setMetadataId(seasonMetadata.getId());
                mediaSeasonMapper.updateById(update);
                season.setMetadataId(seasonMetadata.getId());
            } else if (poster != null) {
                seasonMetadata.setPosterFileNodeId(poster.getId());
                mediaMetadataMapper.updateById(seasonMetadata);
            }
        }
    }

    /**
     * 逐集解析集 NFO 绑定集元数据；无集 NFO 的非手动集回退绑定剧元数据，均记为已匹配。
     */
    private void applyEpisodeNfos(MediaSeries series, String userId, MediaMetadata seriesMetadata) {
        List<MediaItem> episodes = mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                .eq(MediaItem::getSeriesId, series.getId())
                .eq(MediaItem::getItemType, MediaItemType.EPISODE.getCode()));
        for (MediaItem episode : episodes) {
            if (MediaMatchStatus.MANUAL.getCode().equals(episode.getMatchStatus())) {
                continue;
            }
            String metadataId = seriesMetadata.getId();
            FileNode file = fileMapper.selectById(episode.getFileNodeId());
            if (file != null) {
                FileNode nfoNode = persistSupport.findChildFile(userId, file.getParentId(),
                        nfoSupport.nfoNameOf(file.getName()));
                MediaNfoSupport.NfoData data = nfoNode == null ? null : readNfo(nfoNode);
                if (data != null) {
                    FileNode thumb = persistSupport.findChildFile(userId, file.getParentId(),
                            nfoSupport.episodeThumbNameOf(file.getName()));
                    metadataId = nfoSupport.upsertLocalMetadata(episode.getMetadataId(), userId, "episode", data,
                            thumb == null ? null : thumb.getId(), null).getId();
                }
            }
            MediaItem update = new MediaItem();
            update.setId(episode.getId());
            update.setMetadataId(metadataId);
            update.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
            mediaItemMapper.updateById(update);
        }
    }

    private MediaNfoSupport.NfoData readNfo(FileNode nfoNode) {
        byte[] bytes = persistSupport.readFileBytes(nfoNode);
        return bytes == null ? null : nfoSupport.parse(new String(bytes, StandardCharsets.UTF_8));
    }
}
