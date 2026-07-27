package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.vo.TmdbSearchResultVo;
import com.fleyx.jcloud.service.SystemConfigService;
import com.fleyx.jcloud.service.SystemStorageSpaceProvider;
import com.fleyx.jcloud.service.TmdbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * TMDB 元数据服务实现。
 * <p>
 * API Key 与代理由管理员全局配置，存储于系统配置表。
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
    private static final String POSTER_CACHE_DIR = "media/posters";

    private final SystemConfigService systemConfigService;
    private final SystemStorageSpaceProvider systemStorageSpaceProvider;
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
    public MediaMetadata getOrFetch(Long tmdbId, String mediaType) {
        MediaMetadata cached = mediaMetadataMapper.selectOne(new LambdaQueryWrapper<MediaMetadata>()
                .eq(MediaMetadata::getTmdbId, tmdbId)
                .eq(MediaMetadata::getMediaType, mediaType));
        if (cached != null) {
            return cached;
        }
        return fetchAndCache(tmdbId, mediaType);
    }

    @Override
    public MediaMetadata autoMatch(String mediaType, String title, Integer year) {
        if (title == null || title.isBlank()) {
            return null;
        }
        try {
            List<TmdbSearchResultVo> results = search(mediaType, title, year);
            if (results.isEmpty() && year != null) {
                results = search(mediaType, title, null);
            }
            if (results.isEmpty()) {
                return null;
            }
            return getOrFetch(results.getFirst().getTmdbId(), mediaType);
        } catch (Exception e) {
            log.warn("TMDB 自动匹配失败: title={}, year={}, error={}", title, year, e.getMessage());
            return null;
        }
    }

    private MediaMetadata fetchAndCache(Long tmdbId, String mediaType) {
        JsonNode node = requestJson(API_BASE + "/" + mediaType + "/" + tmdbId
                + "?api_key=" + requireApiKey() + "&language=" + LANGUAGE);
        MediaMetadata metadata = new MediaMetadata();
        metadata.setTmdbId(tmdbId);
        metadata.setMediaType(mediaType);
        applyDetail(metadata, node);
        mediaMetadataMapper.insert(metadata);

        metadata.setPosterPath(downloadImage(text(node, "poster_path"), metadata.getId(), "poster"));
        metadata.setBackdropPath(downloadImage(text(node, "backdrop_path"), metadata.getId(), "backdrop"));
        mediaMetadataMapper.updateById(metadata);
        return metadata;
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
        String backdropPath = downloadImage(text(node, "backdrop_path"), metadata.getId(), "backdrop");
        if (backdropPath != null) {
            metadata.setBackdropPath(backdropPath);
        }
        String posterPath = downloadImage(text(node, "poster_path"), metadata.getId(), "poster");
        if (posterPath != null) {
            metadata.setPosterPath(posterPath);
        }
        mediaMetadataMapper.updateById(metadata);
        return metadata;
    }

    @Override
    public void backfillMissingBackdrops() {
        String apiKey = systemConfigService.getValue(CONFIG_KEY_API_KEY, "");
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }
        List<MediaMetadata> missing = mediaMetadataMapper.selectList(new LambdaQueryWrapper<MediaMetadata>()
                .isNull(MediaMetadata::getBackdropPath));
        if (missing.isEmpty()) {
            return;
        }
        log.info("开始补抓 TMDB 背景图，共 {} 条", missing.size());
        for (MediaMetadata metadata : missing) {
            try {
                refresh(metadata.getId());
            } catch (Exception e) {
                log.warn("背景图补抓失败: id={}, error={}", metadata.getId(), e.getMessage());
            }
        }
    }

    /**
     * 将 TMDB 详情响应映射到元数据实体（不含图片）。
     */
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

    private String downloadImage(String tmdbPath, String metadataId, String kind) {
        if (tmdbPath == null) {
            return null;
        }
        String relative = POSTER_CACHE_DIR + "/" + metadataId + "-" + kind + ".jpg";
        try {
            StorageSpace space = systemStorageSpaceProvider.getSystemSpace();
            Path target = Path.of(space.getPath(), "system", relative);
            Files.createDirectories(target.getParent());
            HttpRequest request = HttpRequest.newBuilder(URI.create(IMAGE_BASE + "/w500" + tmdbPath))
                    .timeout(Duration.ofSeconds(30)).GET().build();
            try (InputStream in = buildClient().send(request, HttpResponse.BodyHandlers.ofInputStream()).body()) {
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return relative;
        } catch (Exception e) {
            log.warn("TMDB 图片下载失败: {}, {}", tmdbPath, e.getMessage());
            return null;
        }
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
