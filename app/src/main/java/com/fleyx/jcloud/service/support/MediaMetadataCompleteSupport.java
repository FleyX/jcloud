package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMetadataV2Mapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaSeasonV2Mapper;
import com.fleyx.jcloud.mapper.MediaSeriesV2Mapper;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaMetadataV2;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaSeasonV2;
import com.fleyx.jcloud.model.po.MediaSeriesV2;
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

    private final MediaMetadataV2Mapper mediaMetadataV2Mapper;
    private final MediaMovieMapper mediaMovieMapper;
    private final MediaSeriesV2Mapper mediaSeriesV2Mapper;
    private final MediaSeasonV2Mapper mediaSeasonV2Mapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;

    /**
     * 校验项：字段谓词 + 标识。
     *
     * @param id        校验项标识（title/overview/poster/release_date/vote_average）
     * @param predicate 元数据是否通过该校验
     */
    public record CheckItem(String id, Predicate<MediaMetadataV2> predicate) {
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
    public boolean isComplete(MediaMetadataV2 metadata) {
        return metadata != null && missingItems(metadata).isEmpty();
    }

    /**
     * 未通过的校验项标识列表（调试与日志用），全部通过时为空列表。
     */
    public List<String> missingItems(MediaMetadataV2 metadata) {
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
        MediaMetadataV2 metadata = movie.getMetadataId() == null ? null
                : mediaMetadataV2Mapper.selectById(movie.getMetadataId());
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
    public void refreshSeriesComplete(MediaSeriesV2 series) {
        MediaMetadataV2 metadata = series.getMetadataId() == null ? null
                : mediaMetadataV2Mapper.selectById(series.getMetadataId());
        boolean complete = isComplete(metadata) && seasonsAndEpisodesComplete(series);
        if (Boolean.TRUE.equals(series.getMetadataComplete()) == complete) {
            return;
        }
        mediaSeriesV2Mapper.update(null, new LambdaUpdateWrapper<MediaSeriesV2>()
                .eq(MediaSeriesV2::getId, series.getId())
                .set(MediaSeriesV2::getMetadataComplete, complete));
        series.setMetadataComplete(complete);
        log.debug("剧集完整性重算: series={}, complete={}", series.getId(), complete);
    }

    /**
     * 按 owner 反查并重算完整性（元数据刷新端点用）：movie → 电影行；series → 剧集行（聚合）；
     * season/episode → 其所属剧集行（聚合）。
     */
    public void refreshOwnerComplete(String ownerType, String ownerId) {
        switch (ownerType) {
            case "movie" -> {
                MediaMovie movie = mediaMovieMapper.selectById(ownerId);
                if (movie != null) {
                    refreshMovieComplete(movie);
                }
            }
            case "series" -> {
                MediaSeriesV2 series = mediaSeriesV2Mapper.selectById(ownerId);
                if (series != null) {
                    refreshSeriesComplete(series);
                }
            }
            case "season" -> {
                MediaSeasonV2 season = mediaSeasonV2Mapper.selectById(ownerId);
                if (season != null) {
                    refreshSeriesOf(season.getSeriesId());
                }
            }
            case "episode" -> {
                MediaEpisode episode = mediaEpisodeMapper.selectById(ownerId);
                if (episode != null) {
                    refreshSeriesOf(episode.getSeriesId());
                }
            }
            default -> log.debug("未知元数据归属类型，跳过完整性重算: {}", ownerType);
        }
    }

    private void refreshSeriesOf(String seriesId) {
        MediaSeriesV2 series = mediaSeriesV2Mapper.selectById(seriesId);
        if (series != null) {
            refreshSeriesComplete(series);
        }
    }

    /**
     * 聚合语义：所有实际存在的季、集的元数据都完整。
     */
    private boolean seasonsAndEpisodesComplete(MediaSeriesV2 series) {
        List<MediaSeasonV2> seasons = mediaSeasonV2Mapper.selectList(
                new LambdaQueryWrapper<MediaSeasonV2>().eq(MediaSeasonV2::getSeriesId, series.getId()));
        for (MediaSeasonV2 season : seasons) {
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

    private MediaMetadataV2 metadataOf(String metadataId) {
        return metadataId == null ? null : mediaMetadataV2Mapper.selectById(metadataId);
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }
}
