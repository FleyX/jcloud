package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 媒体元数据完整性判定支撑组件（ADR 0021 / issue #15）。
 * <p>
 * metadata_complete 挂在电影行与剧集行。判定实现为**可扩展的有序校验项列表**
 * （每项 = 字段谓词 + 标识），首版 5 项：标题、简介、海报、发行日期、评分；
 * 每次削刮（自动/手动/刷新）结束后重算。剧集行为聚合语义：剧自身元数据完整
 * 且所有实际存在的季、集的元数据都完整才算完整（任一集缺字段即整部剧不完整）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaMetadataCompleteSupport {

    private final MediaMetadataMapper mediaMetadataMapper;
    private final MediaMovieMapper mediaMovieMapper;
    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;

    /**
     * 校验项：字段谓词 + 标识。
     *
     * @param id        校验项标识（title/overview/poster/release_date/vote_average）
     * @param predicate 元数据是否通过该校验
     */
    public record CheckItem(String id, Predicate<MediaMetadata> predicate) {
    }

    /**
     * 首版 5 项校验，按序判定，新增校验项追加到列表末尾即可。
     */
    private static final List<CheckItem> CHECK_ITEMS = List.of(
            new CheckItem("title", m -> notBlank(m.getTitle())),
            new CheckItem("overview", m -> notBlank(m.getOverview())),
            new CheckItem("poster", m -> m.getPosterFileNodeId() != null),
            new CheckItem("release_date", m -> notBlank(m.getReleaseDate())),
            new CheckItem("vote_average", m -> m.getVoteAverage() != null));

    /**
     * 元数据是否通过全部校验项。
     *
     * @param metadata 元数据，null 视为不完整
     */
    public boolean isComplete(MediaMetadata metadata) {
        return metadata != null && missingItems(metadata).isEmpty();
    }

    /**
     * 未通过的校验项标识列表（调试与日志用），全部通过时为空列表。
     */
    public List<String> missingItems(MediaMetadata metadata) {
        List<String> missing = new ArrayList<>();
        if (metadata == null) {
            return CHECK_ITEMS.stream().map(CheckItem::id).toList();
        }
        for (CheckItem item : CHECK_ITEMS) {
            if (!item.predicate().test(metadata)) {
                missing.add(item.id());
            }
        }
        return missing;
    }

    /**
     * 重算单部电影的完整性标志并落库。
     *
     * @param movie 电影行（行内 metadata_id 需为最新值）
     */
    public void refreshMovieComplete(MediaMovie movie) {
        MediaMetadata metadata = movie.getMetadataId() == null ? null
                : mediaMetadataMapper.selectById(movie.getMetadataId());
        boolean complete = isComplete(metadata);
        if (Boolean.TRUE.equals(movie.getMetadataComplete()) == complete) {
            return;
        }
        mediaMovieMapper.update(null, new LambdaUpdateWrapper<MediaMovie>()
                .eq(MediaMovie::getId, movie.getId())
                .set(MediaMovie::getMetadataComplete, complete));
        movie.setMetadataComplete(complete);
        log.debug("电影完整性重算: movie={}, complete={}, missing={}",
                movie.getId(), complete, missingItems(metadata));
    }

    /**
     * 重算整部剧的完整性标志并落库（聚合语义：剧自身 + 全部实际存在的季、集）。
     *
     * @param series 剧集行（行内 metadata_id 需为最新值）
     */
    public void refreshSeriesComplete(MediaSeries series) {
        MediaMetadata metadata = series.getMetadataId() == null ? null
                : mediaMetadataMapper.selectById(series.getMetadataId());
        boolean complete = isComplete(metadata) && seasonsAndEpisodesComplete(series);
        if (Boolean.TRUE.equals(series.getMetadataComplete()) == complete) {
            return;
        }
        mediaSeriesMapper.update(null, new LambdaUpdateWrapper<MediaSeries>()
                .eq(MediaSeries::getId, series.getId())
                .set(MediaSeries::getMetadataComplete, complete));
        series.setMetadataComplete(complete);
        log.debug("剧集完整性重算: series={}, complete={}", series.getId(), complete);
    }

    /**
     * 按 owner 反查并重算完整性（元数据刷新端点用）：movie → 电影行；series → 剧集行（聚合）；
     * season/episode → 其所属剧集行（聚合）。owner_type 读写统一走枚举（issue #21 审查遗留修复）。
     */
    public void refreshOwnerComplete(MediaMetadataOwnerType ownerType, String ownerId) {
        switch (ownerType) {
            case MOVIE -> {
                MediaMovie movie = mediaMovieMapper.selectById(ownerId);
                if (movie != null) {
                    refreshMovieComplete(movie);
                }
            }
            case SERIES -> {
                MediaSeries series = mediaSeriesMapper.selectById(ownerId);
                if (series != null) {
                    refreshSeriesComplete(series);
                }
            }
            case SEASON -> {
                MediaSeason season = mediaSeasonMapper.selectById(ownerId);
                if (season != null) {
                    refreshSeriesOf(season.getSeriesId());
                }
            }
            case EPISODE -> {
                MediaEpisode episode = mediaEpisodeMapper.selectById(ownerId);
                if (episode != null) {
                    refreshSeriesOf(episode.getSeriesId());
                }
            }
            default -> log.debug("未知元数据归属类型，跳过完整性重算: {}", ownerType);
        }
    }

    private void refreshSeriesOf(String seriesId) {
        MediaSeries series = mediaSeriesMapper.selectById(seriesId);
        if (series != null) {
            refreshSeriesComplete(series);
        }
    }

    /**
     * 聚合语义：所有实际存在的季、集的元数据都完整。
     */
    private boolean seasonsAndEpisodesComplete(MediaSeries series) {
        List<MediaSeason> seasons = mediaSeasonMapper.selectList(
                new LambdaQueryWrapper<MediaSeason>().eq(MediaSeason::getSeriesId, series.getId()));
        for (MediaSeason season : seasons) {
            if (!isComplete(metadataOf(season.getMetadataId()))) {
                return false;
            }
        }
        List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(
                new LambdaQueryWrapper<MediaEpisode>().eq(MediaEpisode::getSeriesId, series.getId()));
        for (MediaEpisode episode : episodes) {
            if (!isComplete(metadataOf(episode.getMetadataId()))) {
                return false;
            }
        }
        return true;
    }

    private MediaMetadata metadataOf(String metadataId) {
        return metadataId == null ? null : mediaMetadataMapper.selectById(metadataId);
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }
}
