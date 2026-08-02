package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.enums.MediaCompleteStatus;
import com.fleyx.jcloud.common.enums.MediaMetadataSource;
import com.fleyx.jcloud.common.enums.MediaPersistStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMetadataV2;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.vo.TmdbSearchResultVo;
import com.fleyx.jcloud.service.SystemConfigService;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.util.TmdbMatchScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TMDB 元数据服务实现：API Key 与代理由管理员配置，元数据按用户隔离（ADR 0020），图片由写回流程下载落盘。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TmdbServiceImpl implements TmdbService {

    /**
     * 系统配置键：TMDB API Key。
     */
    public static final String CONFIG_KEY_API_KEY = "tmdb.api-key";

    /**
     * 系统配置键：TMDB HTTP 代理（host:port），可为空。
     */
    public static final String CONFIG_KEY_PROXY = "tmdb.proxy";

    private static final String API_BASE = "https://api.themoviedb.org/3";
    private static final String IMAGE_BASE = "https://image.tmdb.org/t/p";
    private static final String LANGUAGE = "zh-CN";

    private final SystemConfigService systemConfigService;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<TmdbSearchResultVo> search(String mediaType, String query, Integer year) {
        StringBuilder url = new StringBuilder(API_BASE).append("/search/").append(mediaType)
                .append("?api_key=").append(requireApiKey())
                .append("&language=").append(LANGUAGE)
                .append("&query=").append(java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8));
        if (year != null) {
            url.append("movie".equals(mediaType) ? "&year=" : "&first_air_date_year=").append(year);
        }
        JsonNode root = requestJson(url.toString());
        List<TmdbSearchResultVo> results = new ArrayList<>();
        for (JsonNode node : root.path("results")) {
            TmdbSearchResultVo vo = new TmdbSearchResultVo();
            vo.setTmdbId(node.path("id").asLong());
            vo.setMediaType(mediaType);
            vo.setTitle(text(node, "movie".equals(mediaType) ? "title" : "name"));
            vo.setOriginalTitle(text(node, "movie".equals(mediaType) ? "original_title" : "original_name"));
            vo.setReleaseDate(text(node, "movie".equals(mediaType) ? "release_date" : "first_air_date"));
            vo.setVoteAverage(node.path("vote_average").isNumber() ? node.path("vote_average").asDouble() : null);
            vo.setOverview(text(node, "overview"));
            String posterPath = text(node, "poster_path");
            vo.setPosterUrl(posterPath == null ? null : IMAGE_BASE + "/w342" + posterPath);
            results.add(vo);
            if (results.size() >= 10) {
                break;
            }
        }
        return results;
    }

    @Override
    public MediaMetadata getOrFetch(String userId, Long tmdbId, String mediaType) {
        MediaMetadata cached = mediaMetadataMapper.selectOne(new LambdaQueryWrapper<MediaMetadata>()
                .eq(MediaMetadata::getUserId, userId)
                .eq(MediaMetadata::getMediaType, mediaType)
                .eq(MediaMetadata::getTmdbId, tmdbId));
        if (cached != null) {
            return cached;
        }
        JsonNode node = requestJson(API_BASE + "/" + mediaType + "/" + tmdbId
                + "?api_key=" + requireApiKey() + "&language=" + LANGUAGE);
        MediaMetadata metadata = newTmdbMetadata(userId, tmdbId, mediaType);
        applyDetail(metadata, node);
        mediaMetadataMapper.insert(metadata);
        return metadata;
    }

    @Override
    public MediaMetadata autoMatch(String userId, String mediaType, String title, Integer year) {
        if (title == null || title.isBlank()) {
            return null;
        }
        try {
            TmdbSearchResultVo best = pickBestResult(mediaType, title, year);
            return best == null ? null : getOrFetch(userId, best.getTmdbId(), mediaType);
        } catch (Exception e) {
            log.warn("TMDB 自动匹配失败: title={}, year={}, error={}", title, year, e.getMessage());
            return null;
        }
    }

    /** 搜索 + 候选打分选优（对齐 Jellyfin），无可信匹配返回 null。 */
    private TmdbSearchResultVo pickBestResult(String mediaType, String title, Integer year) {
        List<TmdbSearchResultVo> results = search(mediaType, title, year);
        if (results.isEmpty() && year != null) {
            results = search(mediaType, title, null);
        }
        if (results.isEmpty()) {
            return null;
        }
        TmdbSearchResultVo best = TmdbMatchScorer.pickBest(results, title, year);
        if (best == null) {
            log.info("TMDB 候选均低于匹配阈值: title={}, year={}", title, year);
        }
        return best;
    }

    @Override
    public MediaMetadata getOrFetchSeason(String userId, Long seriesTmdbId, MediaSeason season) {
        if (season.getMetadataId() != null) {
            MediaMetadata bound = mediaMetadataMapper.selectById(season.getMetadataId());
            if (bound != null) {
                return bound;
            }
        }
        JsonNode node = requestJson(API_BASE + "/tv/" + seriesTmdbId + "/season/" + season.getSeasonNo()
                + "?api_key=" + requireApiKey() + "&language=" + LANGUAGE);
        MediaMetadata metadata = newTmdbMetadata(userId,
                node.path("id").isNumber() ? node.path("id").asLong() : null, "season");
        metadata.setTitle(text(node, "name"));
        metadata.setOverview(text(node, "overview"));
        metadata.setReleaseDate(text(node, "air_date"));
        metadata.setVoteAverage(node.path("vote_average").isNumber() ? node.path("vote_average").asDouble() : null);
        metadata.setRawJson(node.toString());
        mediaMetadataMapper.insert(metadata);
        return metadata;
    }

    @Override
    public MediaMetadata getOrFetchEpisode(String userId, MediaItem episode, MediaMetadata seasonMetadata) {
        if (episode.getMetadataId() != null) {
            MediaMetadata bound = mediaMetadataMapper.selectById(episode.getMetadataId());
            if (bound != null && "episode".equals(bound.getMediaType())) {
                return bound;
            }
        }
        if (episode.getEpisodeNo() == null || seasonMetadata == null || seasonMetadata.getRawJson() == null) {
            return null;
        }
        JsonNode ep = findEpisodeNode(seasonMetadata.getRawJson(), episode.getEpisodeNo());
        if (ep == null) {
            return null;
        }
        MediaMetadata metadata = newTmdbMetadata(userId,
                ep.path("id").isNumber() ? ep.path("id").asLong() : null, "episode");
        metadata.setTitle(text(ep, "name"));
        metadata.setOverview(text(ep, "overview"));
        metadata.setReleaseDate(text(ep, "air_date"));
        metadata.setVoteAverage(ep.path("vote_average").isNumber() ? ep.path("vote_average").asDouble() : null);
        metadata.setRawJson(ep.toString());
        mediaMetadataMapper.insert(metadata);
        return metadata;
    }

    /** 从季元数据的 TMDB 原始响应中定位指定集号的集节点。 */
    private JsonNode findEpisodeNode(String seasonRawJson, int episodeNo) {
        try {
            for (JsonNode ep : objectMapper.readTree(seasonRawJson).path("episodes")) {
                if (ep.path("episode_number").isInt() && ep.path("episode_number").asInt() == episodeNo) {
                    return ep;
                }
            }
        } catch (Exception e) {
            log.warn("季元数据原始响应解析失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public MediaMetadata refresh(String metadataId) {
        MediaMetadata metadata = mediaMetadataMapper.selectById(metadataId);
        if (metadata == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "元数据不存在");
        }
        JsonNode node = requestJson(API_BASE + "/" + metadata.getMediaType() + "/" + metadata.getTmdbId()
                + "?api_key=" + requireApiKey() + "&language=" + LANGUAGE);
        applyDetail(metadata, node);
        mediaMetadataMapper.updateById(metadata);
        return metadata;
    }

    // ---------- 新模型（issue #20，t_media_metadata_v2） ----------
    @Override
    public MediaMetadataV2 fetchDetailV2(String userId, Long tmdbId, String mediaType) {
        JsonNode node = requestJson(API_BASE + "/" + mediaType + "/" + tmdbId
                + "?api_key=" + requireApiKey() + "&language=" + LANGUAGE);
        MediaMetadataV2 metadata = newDetachedV2(userId, tmdbId);
        applyDetailV2(metadata, node, "movie".equals(mediaType));
        return metadata;
    }

    @Override
    public MediaMetadataV2 autoMatchV2(String userId, String mediaType, String title, Integer year) {
        if (title == null || title.isBlank()) {
            return null;
        }
        try {
            TmdbSearchResultVo best = pickBestResult(mediaType, title, year);
            return best == null ? null : fetchDetailV2(userId, best.getTmdbId(), mediaType);
        } catch (Exception e) {
            log.warn("TMDB 自动匹配失败: title={}, year={}, error={}", title, year, e.getMessage());
            return null;
        }
    }

    @Override
    public SeasonFetchV2 fetchSeasonV2(String userId, Long seriesTmdbId, Integer seasonNo) {
        if (seriesTmdbId == null || seasonNo == null) {
            return null;
        }
        JsonNode node = requestJson(API_BASE + "/tv/" + seriesTmdbId + "/season/" + seasonNo
                + "?api_key=" + requireApiKey() + "&language=" + LANGUAGE);
        MediaMetadataV2 season = newDetachedV2(userId, nodeIdOrNull(node));
        applyDetailV2(season, node, false);
        Map<Integer, MediaMetadataV2> episodes = new LinkedHashMap<>();
        for (JsonNode ep : node.path("episodes")) {
            MediaMetadataV2 epMeta = newDetachedV2(userId, nodeIdOrNull(ep));
            applyDetailV2(epMeta, ep, false);
            if (ep.path("episode_number").isInt()) {
                episodes.put(ep.path("episode_number").asInt(), epMeta);
            }
        }
        return new SeasonFetchV2(season, episodes);
    }

    @Override
    public MediaMetadataV2 refreshV2(MediaMetadataV2 metadata) {
        if (metadata == null || !MediaMetadataSource.TMDB.getCode().equals(metadata.getSource())
                || metadata.getTmdbId() == null) {
            return metadata;
        }
        String mediaType = "movie".equals(metadata.getOwnerType()) ? "movie"
                : "series".equals(metadata.getOwnerType()) ? "tv" : null;
        if (mediaType == null) {
            return metadata;
        }
        JsonNode node = requestJson(API_BASE + "/" + mediaType + "/" + metadata.getTmdbId()
                + "?api_key=" + requireApiKey() + "&language=" + LANGUAGE);
        applyDetailV2(metadata, node, "movie".equals(mediaType));
        return metadata;
    }

    /** TMDB 响应节点 ID，非数值返回 null。 */
    private Long nodeIdOrNull(JsonNode node) {
        return node.path("id").isNumber() ? node.path("id").asLong() : null;
    }

    /** 新建 TMDB 来源游离元数据（未绑定 owner、未落库，绑定与落库由调用方完成）。 */
    private MediaMetadataV2 newDetachedV2(String userId, Long tmdbId) {
        MediaMetadataV2 metadata = new MediaMetadataV2();
        metadata.setUserId(userId);
        metadata.setTmdbId(tmdbId);
        metadata.setSource(MediaMetadataSource.TMDB.getCode());
        metadata.setPersistStatus(MediaPersistStatus.PENDING.getCode());
        return metadata;
    }

    /** 将 TMDB 详情/季/集响应映射到游离元数据（不含图片）；movie 为电影字段（title/release_date），否则为剧/季/集。 */
    private void applyDetailV2(MediaMetadataV2 metadata, JsonNode node, boolean movie) {
        metadata.setTitle(text(node, movie ? "title" : "name"));
        metadata.setOriginalTitle(text(node, movie ? "original_title" : "original_name"));
        metadata.setOverview(text(node, "overview"));
        metadata.setReleaseDate(text(node, movie ? "release_date" : "air_date"));
        metadata.setVoteAverage(node.path("vote_average").isNumber() ? node.path("vote_average").asDouble() : null);
        List<String> genres = new ArrayList<>();
        for (JsonNode genre : node.path("genres")) {
            genres.add(genre.path("name").asText());
        }
        metadata.setGenres(genres.isEmpty() ? null : String.join(",", genres));
        metadata.setRawJson(node.toString());
    }

    /** 新建 TMDB 来源元数据并写入隔离与状态默认值。 */
    MediaMetadata newTmdbMetadata(String userId, Long tmdbId, String mediaType) {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setUserId(userId);
        metadata.setTmdbId(tmdbId);
        metadata.setMediaType(mediaType);
        metadata.setSource(MediaMetadataSource.TMDB.getCode());
        metadata.setCompleteStatus(MediaCompleteStatus.COMPLETE.getCode());
        metadata.setPersistStatus(MediaPersistStatus.PENDING.getCode());
        return metadata;
    }

    /** 将 TMDB 详情响应映射到元数据实体（不含图片，图片写回为后续阶段）。 */
    private void applyDetail(MediaMetadata metadata, JsonNode node) {
        boolean movie = "movie".equals(metadata.getMediaType());
        metadata.setTitle(text(node, movie ? "title" : "name"));
        metadata.setOriginalTitle(text(node, movie ? "original_title" : "original_name"));
        metadata.setOverview(text(node, "overview"));
        metadata.setReleaseDate(text(node, movie ? "release_date" : "first_air_date"));
        metadata.setVoteAverage(node.path("vote_average").isNumber() ? node.path("vote_average").asDouble() : null);
        List<String> genres = new ArrayList<>();
        for (JsonNode genre : node.path("genres")) {
            genres.add(genre.path("name").asText());
        }
        metadata.setGenres(String.join(",", genres));
        if (!movie) {
            metadata.setSeasonCount(node.path("number_of_seasons").isInt() ? node.path("number_of_seasons").asInt() : null);
        }
        metadata.setRawJson(node.toString());
    }

    @Override
    public byte[] downloadArtwork(String tmdbImagePath, String kind) {
        if (tmdbImagePath == null || tmdbImagePath.isBlank()) {
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(IMAGE_BASE + "/" + resolveImageWidth(kind) + tmdbImagePath))
                    .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<byte[]> response = buildClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("TMDB 图片下载失败: {}, HTTP {}", tmdbImagePath, response.statusCode());
                return null;
            }
            return response.body();
        } catch (Exception e) {
            log.warn("TMDB 图片下载失败: {}, {}", tmdbImagePath, e.getMessage());
            return null;
        }
    }

    /** 根据图片用途解析 TMDB 图片宽度前缀：backdrop 用 w1280，poster/still 等用 w500。 */
    String resolveImageWidth(String kind) {
        return "backdrop".equals(kind) ? "w1280" : "w500";
    }

    private JsonNode requestJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> response = buildClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "TMDB 请求失败: HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "TMDB 请求异常", e);
        }
    }

    private HttpClient buildClient() {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        String proxy = systemConfigService.getValue(CONFIG_KEY_PROXY, "");
        if (proxy != null && !proxy.isBlank()) {
            String[] parts = proxy.split(":");
            builder.proxy(ProxySelector.of(new InetSocketAddress(parts[0], Integer.parseInt(parts[1]))));
        }
        return builder.build();
    }

    private String requireApiKey() {
        String apiKey = systemConfigService.getValue(CONFIG_KEY_API_KEY, "");
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "未配置 TMDB API Key，请联系管理员");
        }
        return apiKey;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
