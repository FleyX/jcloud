package com.fleyx.jcloud.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.enums.MediaMetadataSource;
import com.fleyx.jcloud.common.enums.MediaPersistStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.vo.TmdbSearchResultVo;
import com.fleyx.jcloud.service.SystemConfigService;
import com.fleyx.jcloud.service.TmdbService.SeasonFetchV2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TmdbServiceImpl 单元测试：mock HTTP 客户端返回 TMDB v3 样例 JSON，覆盖搜索/详情/季集解析与异常路径。
 * <p>
 * HTTP 客户端在私有 buildClient() 内部自建且 base URL 为编译期常量，本地 stub 不可达，
 * 因此通过 mockStatic(HttpClient) 拦截 newBuilder 链返回可控响应，解析层全部走真实代码。
 */
class TmdbServiceImplTest {

    private static final String API_KEY = "test-api-key";

    private final SystemConfigService systemConfigService = mock(SystemConfigService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TmdbServiceImpl tmdbService = new TmdbServiceImpl(systemConfigService, objectMapper);

    @BeforeEach
    void stubApiKey() {
        when(systemConfigService.getValue(TmdbServiceImpl.CONFIG_KEY_API_KEY, "")).thenReturn(API_KEY);
    }

    /**
     * 电影搜索：结果字段映射、海报 URL 拼接、请求 URL 含 api_key/language/URL 编码 query/year 参数。
     */
    @Test
    void shouldSearchMovieAndMapFields() throws Exception {
        String json = """
                {
                  "page": 1,
                  "total_results": 1,
                  "total_pages": 1,
                  "results": [
                    {
                      "adult": false,
                      "backdrop_path": "/s3TBrRGB1iav7gFOCNx3H31MoES.jpg",
                      "id": 27205,
                      "original_title": "Inception",
                      "overview": "道姆·柯布是一名经验老道的窃贼，他潜入别人梦境窃取机密。",
                      "popularity": 119.7,
                      "poster_path": "/8IB2e4r4oVhHnANzwmJ1WVK1aA7.jpg",
                      "release_date": "2010-07-16",
                      "title": "盗梦空间",
                      "vote_average": 8.8,
                      "vote_count": 37500
                    }
                  ]
                }
                """;
        try (HttpMock http = mockHttpClient(new StubResponse(200, json))) {
            List<TmdbSearchResultVo> results = tmdbService.search("movie", "盗梦空间", 2010);

            assertEquals(1, results.size());
            TmdbSearchResultVo vo = results.get(0);
            assertEquals(27205L, vo.getTmdbId());
            assertEquals("movie", vo.getMediaType());
            assertEquals("盗梦空间", vo.getTitle());
            assertEquals("Inception", vo.getOriginalTitle());
            assertEquals("2010-07-16", vo.getReleaseDate());
            assertEquals(8.8, vo.getVoteAverage(), 0.0001);
            assertTrue(vo.getOverview().contains("柯布"));
            assertEquals("https://image.tmdb.org/t/p/w342/8IB2e4r4oVhHnANzwmJ1WVK1aA7.jpg", vo.getPosterUrl());

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http.client()).send(captor.capture(), any(HttpResponse.BodyHandler.class));
            assertEquals("https://api.themoviedb.org/3/search/movie?api_key=test-api-key&language=zh-CN"
                    + "&query=%E7%9B%97%E6%A2%A6%E7%A9%BA%E9%97%B4&year=2010", captor.getValue().uri().toString());
        }
    }

    /**
     * 剧集搜索：name/original_name/first_air_date 字段映射与 first_air_date_year 参数。
     */
    @Test
    void shouldSearchTvAndMapSeriesFields() throws Exception {
        String json = """
                {
                  "results": [
                    {
                      "id": 1396,
                      "name": "绝命毒师",
                      "original_name": "Breaking Bad",
                      "first_air_date": "2008-01-20",
                      "overview": "高中化学老师沃尔特·怀特在确诊肺癌后，走上制毒之路。",
                      "poster_path": "/4yFl6y2d3Z2cVkMlXHxqS1lQWtA.jpg",
                      "vote_average": 8.9
                    }
                  ]
                }
                """;
        try (HttpMock http = mockHttpClient(new StubResponse(200, json))) {
            List<TmdbSearchResultVo> results = tmdbService.search("tv", "Breaking Bad", 2008);

            assertEquals(1, results.size());
            TmdbSearchResultVo vo = results.get(0);
            assertEquals(1396L, vo.getTmdbId());
            assertEquals("tv", vo.getMediaType());
            assertEquals("绝命毒师", vo.getTitle());
            assertEquals("Breaking Bad", vo.getOriginalTitle());
            assertEquals("2008-01-20", vo.getReleaseDate());
            assertEquals(8.9, vo.getVoteAverage(), 0.0001);

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http.client()).send(captor.capture(), any(HttpResponse.BodyHandler.class));
            assertTrue(captor.getValue().uri().toString().contains(
                    "/search/tv?api_key=test-api-key&language=zh-CN&query=Breaking+Bad&first_air_date_year=2008"));
        }
    }

    /**
     * 空结果与 year=null：返回空列表，请求 URL 不带 year 参数。
     */
    @Test
    void shouldReturnEmptyResultsWhenNoMatch() throws Exception {
        String json = """
                {"page": 1, "total_results": 0, "total_pages": 1, "results": []}
                """;
        try (HttpMock http = mockHttpClient(new StubResponse(200, json))) {
            List<TmdbSearchResultVo> results = tmdbService.search("tv", "不存在的剧", null);

            assertTrue(results.isEmpty());

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http.client()).send(captor.capture(), any(HttpResponse.BodyHandler.class));
            String url = captor.getValue().uri().toString();
            assertTrue(url.contains("/search/tv?api_key=test-api-key&language=zh-CN"));
            assertFalse(url.contains("year="));
        }
    }

    /**
     * 缺字段容错：id 缺失/非数值、vote_average 非数值、overview 空白、poster_path 缺失均不抛异常。
     */
    @Test
    void shouldTolerateMissingAndInvalidFields() throws Exception {
        String json = """
                {
                  "results": [
                    {"id": 123, "vote_average": "8.8", "overview": "", "poster_path": null},
                    {}
                  ]
                }
                """;
        try (HttpMock http = mockHttpClient(new StubResponse(200, json))) {
            List<TmdbSearchResultVo> results = tmdbService.search("movie", "无字段", null);

            assertEquals(2, results.size());
            TmdbSearchResultVo first = results.get(0);
            assertEquals(123L, first.getTmdbId());
            assertNull(first.getTitle());
            assertNull(first.getOriginalTitle());
            assertNull(first.getReleaseDate());
            assertNull(first.getVoteAverage());
            assertNull(first.getOverview());
            assertNull(first.getPosterUrl());

            TmdbSearchResultVo second = results.get(1);
            assertEquals(0L, second.getTmdbId());
            assertNull(second.getTitle());
        }
    }

    /**
     * 结果截断：超过 10 条只返回前 10 条。
     */
    @Test
    void shouldCapResultsAtTen() throws Exception {
        StringBuilder json = new StringBuilder("{\"results\":[");
        for (int i = 1; i <= 12; i++) {
            if (i > 1) {
                json.append(',');
            }
            json.append("{\"id\":").append(i).append('}');
        }
        json.append("]}");
        try (HttpMock http = mockHttpClient(new StubResponse(200, json.toString()))) {
            List<TmdbSearchResultVo> results = tmdbService.search("movie", "列表", null);

            assertEquals(10, results.size());
            assertEquals(1L, results.get(0).getTmdbId());
            assertEquals(10L, results.get(9).getTmdbId());
        }
    }

    /**
     * API 非 200 响应（401/404/500）：抛 BusinessException（BUSINESS_ERROR），消息含 HTTP 状态码。
     * <p>
     * 注意：被测代码对非 200 抛 BusinessException 而非 SystemException（AGENTS.md 的
     * "调用报错抛 SystemException" 仅覆盖传输/解析异常），此处按真实行为断言。
     */
    @Test
    void shouldThrowBusinessExceptionOnHttpErrorStatus() throws Exception {
        for (int status : new int[]{401, 404, 500}) {
            try (HttpMock http = mockHttpClient(new StubResponse(status, "{}"))) {
                BusinessException exception = assertThrows(BusinessException.class,
                        () -> tmdbService.search("movie", "Inception", 2010));

                assertEquals(ResultCode.BUSINESS_ERROR, exception.getResultCode());
                assertTrue(exception.getMessage().contains("HTTP " + status));
            }
        }
    }

    /**
     * 传输异常（连接失败/超时）：抛 SystemException（SYSTEM_ERROR）且带原异常 cause。
     */
    @Test
    void shouldThrowSystemExceptionWithCauseOnTransportError() throws Exception {
        try (HttpMock http = mockHttpClientFailure(new IOException("connection refused"))) {
            SystemException exception = assertThrows(SystemException.class,
                    () -> tmdbService.search("movie", "Inception", 2010));

            assertEquals(ResultCode.SYSTEM_ERROR, exception.getResultCode());
            assertEquals("TMDB 请求异常", exception.getMessage());
            assertInstanceOf(IOException.class, exception.getCause());
        }
    }

    /**
     * 响应体非法 JSON：抛 SystemException（SYSTEM_ERROR）且 cause 为 JSON 解析异常。
     */
    @Test
    void shouldThrowSystemExceptionWithCauseOnMalformedJson() throws Exception {
        try (HttpMock http = mockHttpClient(new StubResponse(200, "not-a-json"))) {
            SystemException exception = assertThrows(SystemException.class,
                    () -> tmdbService.search("movie", "Inception", 2010));

            assertEquals(ResultCode.SYSTEM_ERROR, exception.getResultCode());
            assertEquals("TMDB 请求异常", exception.getMessage());
            assertInstanceOf(JsonProcessingException.class, exception.getCause());
        }
    }

    /**
     * 未配置 API Key：在发起 HTTP 前抛 BusinessException，消息提示联系管理员。
     */
    @Test
    void shouldThrowBusinessExceptionWhenApiKeyMissing() {
        when(systemConfigService.getValue(TmdbServiceImpl.CONFIG_KEY_API_KEY, "")).thenReturn("");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> tmdbService.search("movie", "Inception", 2010));

        assertEquals(ResultCode.BUSINESS_ERROR, exception.getResultCode());
        assertTrue(exception.getMessage().contains("未配置 TMDB API Key"));
    }

    /**
     * 电影详情：title/release_date/genres 拼接/rawJson 落位与游离元数据（source/persistStatus/userId）映射。
     */
    @Test
    void shouldFetchMovieDetailV2() throws Exception {
        String json = """
                {
                  "adult": false,
                  "id": 27205,
                  "title": "盗梦空间",
                  "original_title": "Inception",
                  "overview": "道姆·柯布是一名经验老道的窃贼，他潜入别人梦境窃取机密。",
                  "release_date": "2010-07-16",
                  "vote_average": 8.8,
                  "genres": [{"id": 28, "name": "动作"}, {"id": 878, "name": "科幻"}],
                  "poster_path": "/8IB2e4r4oVhHnANzwmJ1WVK1aA7.jpg",
                  "status": "Released"
                }
                """;
        try (HttpMock http = mockHttpClient(new StubResponse(200, json))) {
            MediaMetadata metadata = tmdbService.fetchDetailV2("user-1", 27205L, "movie");

            assertEquals("user-1", metadata.getUserId());
            assertEquals(27205L, metadata.getTmdbId());
            assertEquals(MediaMetadataSource.TMDB.getCode(), metadata.getSource());
            assertEquals(MediaPersistStatus.PENDING.getCode(), metadata.getPersistStatus());
            assertEquals("盗梦空间", metadata.getTitle());
            assertEquals("Inception", metadata.getOriginalTitle());
            assertEquals("2010-07-16", metadata.getReleaseDate());
            assertEquals(8.8, metadata.getVoteAverage(), 0.0001);
            assertEquals("动作,科幻", metadata.getGenres());
            assertTrue(metadata.getOverview().contains("柯布"));
            assertNotNull(metadata.getRawJson());
            assertTrue(metadata.getRawJson().contains("27205"));
            assertTrue(metadata.getRawJson().contains("\"title\":\"盗梦空间\""));

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http.client()).send(captor.capture(), any(HttpResponse.BodyHandler.class));
            assertEquals("https://api.themoviedb.org/3/movie/27205?api_key=test-api-key&language=zh-CN",
                    captor.getValue().uri().toString());
        }
    }

    /**
     * 剧集详情：name/original_name 字段映射，日期读取顶层 first_air_date（非 air_date）。
     * <p>
     * 历史缺陷：曾读取 air_date 导致剧集详情 releaseDate 恒为 null，已修复为先读 first_air_date、为空再兜底 air_date。
     */
    @Test
    void shouldFetchTvDetailV2AndMapSeriesFields() throws Exception {
        String json = """
                {
                  "id": 1396,
                  "name": "绝命毒师",
                  "original_name": "Breaking Bad",
                  "overview": "高中化学老师沃尔特·怀特在确诊肺癌后，走上制毒之路。",
                  "first_air_date": "2008-01-20",
                  "vote_average": 8.9,
                  "genres": [{"id": 18, "name": "剧情"}, {"id": 80, "name": "犯罪"}],
                  "poster_path": "/4yFl6y2d3Z2cVkMlXHxqS1lQWtA.jpg"
                }
                """;
        try (HttpMock http = mockHttpClient(new StubResponse(200, json))) {
            MediaMetadata metadata = tmdbService.fetchDetailV2("user-1", 1396L, "tv");

            assertEquals("user-1", metadata.getUserId());
            assertEquals(1396L, metadata.getTmdbId());
            assertEquals("绝命毒师", metadata.getTitle());
            assertEquals("Breaking Bad", metadata.getOriginalTitle());
            assertEquals("2008-01-20", metadata.getReleaseDate());
            assertEquals(8.9, metadata.getVoteAverage(), 0.0001);
            assertEquals("剧情,犯罪", metadata.getGenres());

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http.client()).send(captor.capture(), any(HttpResponse.BodyHandler.class));
            assertEquals("https://api.themoviedb.org/3/tv/1396?api_key=test-api-key&language=zh-CN",
                    captor.getValue().uri().toString());
        }
    }

    /**
     * 季数据：季元数据 + 季内集按集号索引，季/集均映射 air_date 等字段。
     */
    @Test
    void shouldFetchSeasonV2WithEpisodes() throws Exception {
        String json = """
                {
                  "id": 1396,
                  "name": "绝命毒师 第一季",
                  "overview": "第一季剧情简介。",
                  "air_date": "2008-01-20",
                  "season_number": 1,
                  "poster_path": "/4yFl6y2d3Z2cVkMlXHxqS1lQWtA.jpg",
                  "episodes": [
                    {
                      "id": 62085,
                      "name": "试播集",
                      "overview": "沃尔特·怀特得知自己患上了肺癌。",
                      "air_date": "2008-01-20",
                      "episode_number": 1,
                      "vote_average": 8.5
                    },
                    {
                      "id": 62086,
                      "name": "猫袋里的猫",
                      "overview": "沃尔特与杰西达成交易。",
                      "air_date": "2008-01-27",
                      "episode_number": 2,
                      "vote_average": 8.3
                    }
                  ]
                }
                """;
        try (HttpMock http = mockHttpClient(new StubResponse(200, json))) {
            SeasonFetchV2 seasonFetch = tmdbService.fetchSeasonV2("user-1", 1396L, 1);

            MediaMetadata season = seasonFetch.season();
            assertEquals("user-1", season.getUserId());
            assertEquals(1396L, season.getTmdbId());
            assertEquals("绝命毒师 第一季", season.getTitle());
            assertEquals("2008-01-20", season.getReleaseDate());
            assertNull(season.getVoteAverage());

            assertEquals(2, seasonFetch.episodes().size());
            MediaMetadata episode1 = seasonFetch.episodes().get(1);
            assertEquals(62085L, episode1.getTmdbId());
            assertEquals("试播集", episode1.getTitle());
            assertEquals("2008-01-20", episode1.getReleaseDate());
            assertEquals(8.5, episode1.getVoteAverage(), 0.0001);
            MediaMetadata episode2 = seasonFetch.episodes().get(2);
            assertEquals(62086L, episode2.getTmdbId());
            assertEquals("猫袋里的猫", episode2.getTitle());
            assertEquals("user-1", episode2.getUserId());
            assertEquals(MediaMetadataSource.TMDB.getCode(), episode2.getSource());
            assertEquals(MediaPersistStatus.PENDING.getCode(), episode2.getPersistStatus());

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http.client()).send(captor.capture(), any(HttpResponse.BodyHandler.class));
            assertEquals("https://api.themoviedb.org/3/tv/1396/season/1?api_key=test-api-key&language=zh-CN",
                    captor.getValue().uri().toString());
        }
    }

    /**
     * 季号/剧 ID 为空：直接返回 null，不发 HTTP 请求。
     */
    @Test
    void shouldReturnNullWhenSeasonArgsInvalid() {
        assertNull(tmdbService.fetchSeasonV2("user-1", null, 1));
        assertNull(tmdbService.fetchSeasonV2("user-1", 1396L, null));
    }

    /**
     * 自动匹配：搜索候选打分选中最优后拉取详情，两次请求均发往正确端点。
     */
    @Test
    void shouldAutoMatchPickBestAndFetchDetail() throws Exception {
        String searchJson = """
                {
                  "results": [
                    {
                      "id": 27205,
                      "title": "盗梦空间",
                      "original_title": "Inception",
                      "release_date": "2010-07-16",
                      "vote_average": 8.8
                    },
                    {
                      "id": 999,
                      "title": "盗梦空间2",
                      "original_title": "Inception 2",
                      "release_date": "2010-07-16",
                      "vote_average": 5.0
                    }
                  ]
                }
                """;
        String detailJson = """
                {
                  "id": 27205,
                  "title": "盗梦空间",
                  "original_title": "Inception",
                  "overview": "道姆·柯布是一名经验老道的窃贼。",
                  "release_date": "2010-07-16",
                  "vote_average": 8.8,
                  "genres": [{"id": 878, "name": "科幻"}]
                }
                """;
        try (HttpMock http = mockHttpClient(new StubResponse(200, searchJson), new StubResponse(200, detailJson))) {
            MediaMetadata metadata = tmdbService.autoMatchV2("user-1", "movie", "盗梦空间", 2010);

            assertNotNull(metadata);
            assertEquals(27205L, metadata.getTmdbId());
            assertEquals("user-1", metadata.getUserId());
            assertEquals("盗梦空间", metadata.getTitle());

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http.client(), times(2)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
            List<HttpRequest> requests = captor.getAllValues();
            assertTrue(requests.get(0).uri().toString().contains("/search/movie?"));
            assertTrue(requests.get(1).uri().toString().contains("/movie/27205?"));
        }
    }

    /**
     * 自动匹配：无可信候选（空结果）返回 null，不发详情请求。
     * <p>
     * year 传 null 避免 pickBestResult 因空结果重试一次无年份搜索，确保只发一次请求。
     */
    @Test
    void shouldAutoMatchReturnNullWhenNoCandidate() throws Exception {
        String json = """
                {"page": 1, "total_results": 0, "total_pages": 1, "results": []}
                """;
        try (HttpMock http = mockHttpClient(new StubResponse(200, json))) {
            assertNull(tmdbService.autoMatchV2("user-1", "movie", "不存在的电影", null));

            verify(http.client(), times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
    }

    /**
     * 自动匹配：搜索接口报错（如 401）被内部吞掉并返回 null。
     */
    @Test
    void shouldAutoMatchSwallowApiError() throws Exception {
        try (HttpMock http = mockHttpClient(new StubResponse(401, "{}"))) {
            assertNull(tmdbService.autoMatchV2("user-1", "movie", "盗梦空间", 2010));
        }
    }

    /**
     * 自动匹配：空标题直接返回 null，不发 HTTP 请求。
     */
    @Test
    void shouldAutoMatchReturnNullForBlankTitle() {
        assertNull(tmdbService.autoMatchV2("user-1", "movie", null, 2010));
        assertNull(tmdbService.autoMatchV2("user-1", "movie", "   ", 2010));
    }

    /**
     * 图片下载：200 返回字节，poster 使用 w500 前缀。
     */
    @Test
    void shouldDownloadArtworkReturnBytesAndBuildPosterUrl() throws Exception {
        byte[] bytes = "fake-image-bytes".getBytes(StandardCharsets.UTF_8);
        try (HttpMock http = mockHttpClient(new StubResponse(200, bytes))) {
            byte[] result = tmdbService.downloadArtwork("/8IB2e4r4oVhHnANzwmJ1WVK1aA7.jpg", "poster");

            assertArrayEquals(bytes, result);

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http.client()).send(captor.capture(), any(HttpResponse.BodyHandler.class));
            assertEquals("https://image.tmdb.org/t/p/w500/8IB2e4r4oVhHnANzwmJ1WVK1aA7.jpg",
                    captor.getValue().uri().toString());
        }
    }

    /**
     * 图片下载：backdrop 使用 w1280 前缀。
     */
    @Test
    void shouldDownloadArtworkUseBackdropWidth() throws Exception {
        byte[] bytes = "fake-backdrop-bytes".getBytes(StandardCharsets.UTF_8);
        try (HttpMock http = mockHttpClient(new StubResponse(200, bytes))) {
            byte[] result = tmdbService.downloadArtwork("/s3TBrRGB1iav7gFOCNx3H31MoES.jpg", "backdrop");

            assertArrayEquals(bytes, result);

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http.client()).send(captor.capture(), any(HttpResponse.BodyHandler.class));
            assertEquals("https://image.tmdb.org/t/p/w1280/s3TBrRGB1iav7gFOCNx3H31MoES.jpg",
                    captor.getValue().uri().toString());
        }
    }

    /**
     * 图片下载：非 200 与传输异常均返回 null（不抛异常）。
     */
    @Test
    void shouldDownloadArtworkReturnNullOnHttpFailure() throws Exception {
        try (HttpMock http = mockHttpClient(new StubResponse(404, "not found".getBytes(StandardCharsets.UTF_8)))) {
            assertNull(tmdbService.downloadArtwork("/poster.jpg", "poster"));
        }
        try (HttpMock http = mockHttpClientFailure(new IOException("boom"))) {
            assertNull(tmdbService.downloadArtwork("/poster.jpg", "poster"));
        }
    }

    /**
     * 图片下载：路径为空/空白直接返回 null，不发 HTTP 请求。
     */
    @Test
    void shouldDownloadArtworkReturnNullForBlankPath() {
        assertNull(tmdbService.downloadArtwork(null, "poster"));
        assertNull(tmdbService.downloadArtwork("   ", "poster"));
    }

    /**
     * 图片宽度解析：backdrop 用 w1280，其余（poster/still/未知）用 w500。
     */
    @Test
    void shouldResolveImageWidthByKind() {
        assertEquals("w1280", tmdbService.resolveImageWidth("backdrop"));
        assertEquals("w500", tmdbService.resolveImageWidth("poster"));
        assertEquals("w500", tmdbService.resolveImageWidth("still"));
        assertEquals("w500", tmdbService.resolveImageWidth("unknown"));
    }

    /**
     * 单次 HTTP 响应桩：状态码 + 响应体（搜索/详情为 String JSON，图片下载为 byte[]）。
     */
    private record StubResponse(int statusCode, Object body) {
    }

    /**
     * HTTP 客户端静态 mock 句柄，关闭时释放 mockStatic 作用域。
     */
    private record HttpMock(HttpClient client, MockedStatic<HttpClient> staticMock) implements AutoCloseable {

        @Override
        public void close() {
            staticMock.close();
        }
    }

    /**
     * 拦截 HttpClient.newBuilder()，按调用顺序返回给定响应；连续调用超出时复用最后一个。
     */
    @SuppressWarnings("unchecked")
    private HttpMock mockHttpClient(StubResponse... responses) throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpClient.Builder builder = mock(HttpClient.Builder.class);
        when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.proxy(any(ProxySelector.class))).thenReturn(builder);
        when(builder.build()).thenReturn(client);

        AtomicInteger counter = new AtomicInteger();
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            StubResponse response = responses[Math.min(counter.getAndIncrement(), responses.length - 1)];
            HttpResponse<Object> httpResponse = mock(HttpResponse.class);
            when(httpResponse.statusCode()).thenReturn(response.statusCode());
            when(httpResponse.body()).thenReturn(response.body());
            return httpResponse;
        });

        MockedStatic<HttpClient> staticMock = Mockito.mockStatic(HttpClient.class);
        staticMock.when(() -> HttpClient.newBuilder()).thenReturn(builder);
        return new HttpMock(client, staticMock);
    }

    /**
     * 拦截 HttpClient.newBuilder()，send 直接抛出给定异常（模拟连接失败/超时）。
     */
    private HttpMock mockHttpClientFailure(Throwable throwable) throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpClient.Builder builder = mock(HttpClient.Builder.class);
        when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.proxy(any(ProxySelector.class))).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(throwable);

        MockedStatic<HttpClient> staticMock = Mockito.mockStatic(HttpClient.class);
        staticMock.when(() -> HttpClient.newBuilder()).thenReturn(builder);
        return new HttpMock(client, staticMock);
    }
}
