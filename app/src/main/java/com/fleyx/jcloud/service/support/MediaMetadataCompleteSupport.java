package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 媒体元数据完整性判定支撑组件（ADR 0021 / issue #15，issue #02 升级）。
 * <p>
 * metadata_complete 挂在电影行与剧集行。判定实现为**可扩展的有序校验项列表**
 * （每项 = 字段谓词 + 标识），首版 5 项：标题、简介、海报、发行日期、评分；
 * 每次削刮（自动/手动/刷新）结束后重算。剧集行为聚合语义：剧自身元数据完整
 * 且所有实际存在的季、集的元数据都完整才算完整（任一集缺字段即整部剧不完整）。
 * <p>
 * poster 校验项从「指针非空」升级为「指针非空且 FileNode 真实存在」（issue #02）：
 * CheckItem 谓词保持纯内存（季/集循环调用，谓词里查 DB 会 N+1），存在性判定叠加在
 * 重算入口批量执行——一次 IN 查询得存活 id 集合。回收站即物理删行（t_file_node
 * 行已删），t_file_node 存在即有效。backdrop 不纳入完整性（缺失补回由削刮候选处理）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaMetadataCompleteSupport {

    private final FileMapper fileMapper;
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
     * poster 校验项标识（存在性判定在重算入口叠加，标识对外不变）。
     */
    private static final String POSTER_ITEM_ID = "poster";

    /**
     * 首版 5 项校验，按序判定，新增校验项追加到列表末尾即可。
     * 谓词保持纯内存；poster 的 FileNode 存在性校验见 {@link #completeOf}。
     */
    private static final List<CheckItem> CHECK_ITEMS = List.of(
            new CheckItem("title", m -> notBlank(m.getTitle())),
            new CheckItem("overview", m -> notBlank(m.getOverview())),
            new CheckItem(POSTER_ITEM_ID, m -> m.getPosterFileNodeId() != null),
            new CheckItem("release_date", m -> notBlank(m.getReleaseDate())),
            new CheckItem("vote_average", m -> m.getVoteAverage() != null));

    /**
     * 元数据是否通过全部校验项（纯内存判定，不含 FileNode 存在性）。
     *
     * @param metadata 元数据，null 视为不完整
     */
    public boolean isComplete(MediaMetadata metadata) {
        return metadata != null && missingItems(metadata).isEmpty();
    }

    /**
     * 未通过的校验项标识列表（调试与日志用），全部通过时为空列表（纯内存判定）。
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
        Set<String> alivePosterIds = filterExistingNodeIds(metadata == null ? List.of()
                : Collections.singletonList(metadata.getPosterFileNodeId()));
        refreshMovieComplete(movie, metadata, alivePosterIds);
    }

    private void refreshMovieComplete(MediaMovie movie, MediaMetadata metadata, Set<String> alivePosterIds) {
        boolean complete = completeOf(metadata, alivePosterIds);
        if (Boolean.TRUE.equals(movie.getMetadataComplete()) == complete) {
            return;
        }
        mediaMovieMapper.update(null, new LambdaUpdateWrapper<MediaMovie>()
                .eq(MediaMovie::getId, movie.getId())
                .set(MediaMovie::getMetadataComplete, complete));
        movie.setMetadataComplete(complete);
        log.debug("电影完整性重算: movie={}, complete={}, missing={}",
                movie.getId(), complete, missingItemsWithAlive(metadata, alivePosterIds));
    }

    /**
     * 重算整部剧的完整性标志并落库（聚合语义：剧自身 + 全部实际存在的季、集）。
     *
     * @param series 剧集行（行内 metadata_id 需为最新值）
     */
    public void refreshSeriesComplete(MediaSeries series) {
        List<MediaSeason> seasons = mediaSeasonMapper.selectList(
                new LambdaQueryWrapper<MediaSeason>().eq(MediaSeason::getSeriesId, series.getId()));
        List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(
                new LambdaQueryWrapper<MediaEpisode>().eq(MediaEpisode::getSeriesId, series.getId()));
        Map<String, MediaMetadata> metadataById = metadataMapOf(seriesMetadataIds(series, seasons, episodes));
        Set<String> alivePosterIds = filterExistingNodeIds(posterNodeIdsOf(metadataById));
        refreshSeriesComplete(series, metadataById, seasons, episodes, alivePosterIds);
    }

    private void refreshSeriesComplete(MediaSeries series, Map<String, MediaMetadata> metadataById,
                                       List<MediaSeason> seasons, List<MediaEpisode> episodes,
                                       Set<String> alivePosterIds) {
        MediaMetadata metadata = series.getMetadataId() == null ? null : metadataById.get(series.getMetadataId());
        boolean complete = completeOf(metadata, alivePosterIds)
                && seasonsAndEpisodesComplete(seasons, episodes, metadataById, alivePosterIds);
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
     * 扫描末尾批量重算本库电影完整性（issue #02）：查该库 metadata_id IS NOT NULL 的电影行，
     * 合并所有涉及元数据行的 posterFileNodeId 做**一次** IN 查询，逐条走重算（「值变化才落库」幂等写）。
     *
     * @param directoryId 媒体库 ID
     */
    public void refreshMoviesCompleteByDirectory(String directoryId) {
        List<MediaMovie> movies = mediaMovieMapper.selectList(new LambdaQueryWrapper<MediaMovie>()
                .eq(MediaMovie::getDirectoryId, directoryId)
                .isNotNull(MediaMovie::getMetadataId));
        if (movies.isEmpty()) {
            return;
        }
        Map<String, MediaMetadata> metadataById = metadataMapOf(
                movies.stream().map(MediaMovie::getMetadataId).toList());
        Set<String> alivePosterIds = filterExistingNodeIds(posterNodeIdsOf(metadataById));
        for (MediaMovie movie : movies) {
            refreshMovieComplete(movie, metadataById.get(movie.getMetadataId()), alivePosterIds);
        }
    }

    /**
     * 扫描末尾批量重算本库剧集完整性（issue #02）：查该库 metadata_id IS NOT NULL 的剧集行，
     * 下钻其全部季/集元数据行，合并所有涉及元数据行的 posterFileNodeId 做**一次** IN 查询，
     * 逐条走聚合重算（「值变化才落库」幂等写）。
     *
     * @param directoryId 媒体库 ID
     */
    public void refreshSeriesCompleteByDirectory(String directoryId) {
        List<MediaSeries> seriesList = mediaSeriesMapper.selectList(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getDirectoryId, directoryId)
                .isNotNull(MediaSeries::getMetadataId));
        if (seriesList.isEmpty()) {
            return;
        }
        List<String> seriesIds = seriesList.stream().map(MediaSeries::getId).toList();
        List<MediaSeason> seasons = mediaSeasonMapper.selectList(new LambdaQueryWrapper<MediaSeason>()
                .in(MediaSeason::getSeriesId, seriesIds));
        List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                .in(MediaEpisode::getSeriesId, seriesIds));
        Map<String, MediaMetadata> metadataById = metadataMapOf(seriesMetadataIds(seriesList, seasons, episodes));
        Set<String> alivePosterIds = filterExistingNodeIds(posterNodeIdsOf(metadataById));
        Map<String, List<MediaSeason>> seasonsBySeries = seasons.stream()
                .collect(Collectors.groupingBy(MediaSeason::getSeriesId));
        Map<String, List<MediaEpisode>> episodesBySeries = episodes.stream()
                .collect(Collectors.groupingBy(MediaEpisode::getSeriesId));
        for (MediaSeries series : seriesList) {
            refreshSeriesComplete(series, metadataById,
                    seasonsBySeries.getOrDefault(series.getId(), List.of()),
                    episodesBySeries.getOrDefault(series.getId(), List.of()),
                    alivePosterIds);
        }
    }

    /**
     * 全项校验（poster 项叠加 FileNode 存在性：指针非空且存活；回收站即物理删行，行存在即有效）。
     */
    private boolean completeOf(MediaMetadata metadata, Set<String> alivePosterNodeIds) {
        return metadata != null && missingItemsWithAlive(metadata, alivePosterNodeIds).isEmpty();
    }

    /**
     * 未通过的校验项标识列表（含 poster 存在性判定），全部通过时为空列表。
     */
    private List<String> missingItemsWithAlive(MediaMetadata metadata, Set<String> alivePosterNodeIds) {
        if (metadata == null) {
            return CHECK_ITEMS.stream().map(CheckItem::id).toList();
        }
        List<String> missing = new ArrayList<>();
        for (CheckItem item : CHECK_ITEMS) {
            if (item.id().equals(POSTER_ITEM_ID)) {
                if (metadata.getPosterFileNodeId() == null
                        || !alivePosterNodeIds.contains(metadata.getPosterFileNodeId())) {
                    missing.add(item.id());
                }
            } else if (!item.predicate().test(metadata)) {
                missing.add(item.id());
            }
        }
        return missing;
    }

    /**
     * 聚合语义：所有实际存在的季、集的元数据都完整。
     */
    private boolean seasonsAndEpisodesComplete(List<MediaSeason> seasons, List<MediaEpisode> episodes,
                                               Map<String, MediaMetadata> metadataById,
                                               Set<String> alivePosterIds) {
        for (MediaSeason season : seasons) {
            if (!completeOf(metadataById.get(season.getMetadataId()), alivePosterIds)) {
                return false;
            }
        }
        for (MediaEpisode episode : episodes) {
            if (!completeOf(metadataById.get(episode.getMetadataId()), alivePosterIds)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 一次 IN 查询返回存活的 FileNode id 集合（仅按 id 存活判定，回收站即物理删行，行存在即有效）。
     */
    private Set<String> filterExistingNodeIds(Collection<String> ids) {
        List<String> nonNullIds = ids.stream().filter(Objects::nonNull).toList();
        if (nonNullIds.isEmpty()) {
            return Set.of();
        }
        return fileMapper.selectList(new LambdaQueryWrapper<FileNode>()
                        .in(FileNode::getId, nonNullIds)
                        .select(FileNode::getId))
                .stream().map(FileNode::getId).collect(Collectors.toSet());
    }

    /**
     * 涉及元数据行的 posterFileNodeId 列表（含 null，交由 {@link #filterExistingNodeIds} 过滤）。
     */
    private List<String> posterNodeIdsOf(Map<String, MediaMetadata> metadataById) {
        return metadataById.values().stream().map(MediaMetadata::getPosterFileNodeId).toList();
    }

    /**
     * 按 id 批量加载元数据行；入参去空去重（缺键的 metadataId 视为无元数据，即不完整）。
     */
    private Map<String, MediaMetadata> metadataMapOf(List<String> metadataIds) {
        List<String> nonNullIds = metadataIds.stream().filter(Objects::nonNull).distinct().toList();
        if (nonNullIds.isEmpty()) {
            return Map.of();
        }
        return mediaMetadataMapper.selectBatchIds(nonNullIds).stream()
                .collect(Collectors.toMap(MediaMetadata::getId, Function.identity()));
    }

    private List<String> seriesMetadataIds(MediaSeries series, List<MediaSeason> seasons,
                                           List<MediaEpisode> episodes) {
        return seriesMetadataIds(List.of(series), seasons, episodes);
    }

    private List<String> seriesMetadataIds(List<MediaSeries> seriesList, List<MediaSeason> seasons,
                                           List<MediaEpisode> episodes) {
        List<String> metadataIds = new ArrayList<>();
        seriesList.stream().map(MediaSeries::getMetadataId).filter(Objects::nonNull).forEach(metadataIds::add);
        seasons.stream().map(MediaSeason::getMetadataId).filter(Objects::nonNull).forEach(metadataIds::add);
        episodes.stream().map(MediaEpisode::getMetadataId).filter(Objects::nonNull).forEach(metadataIds::add);
        return metadataIds;
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }
}
