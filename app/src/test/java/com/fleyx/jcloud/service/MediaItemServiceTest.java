package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.common.enums.MediaMetadataSource;
import com.fleyx.jcloud.common.enums.MediaPersistStatus;
import com.fleyx.jcloud.common.enums.MediaType;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.MediaGenreVo;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaMovieVersionVo;
import com.fleyx.jcloud.model.vo.MediaSearchResultVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesVo;
import com.fleyx.jcloud.util.IdUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体条目查询服务测试。
 * <p>
 * 电视剧相关查询已切换到新模型表（issue #17）：t_media_series_v2 / t_media_season_v2 / t_media_episode。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaItemServiceTest {

    @Autowired
    private MediaItemService mediaItemService;

    @Autowired
    private MediaSeriesMapper mediaSeriesMapper;

    @Autowired
    private MediaSeasonMapper mediaSeasonMapper;

    @Autowired
    private MediaEpisodeMapper mediaEpisodeMapper;

    @Autowired
    private MediaMovieMapper mediaMovieMapper;

    @Autowired
    private MediaMovieFileMapper mediaMovieFileMapper;

    @Autowired
    private MediaOtherMapper mediaOtherMapper;

    @Autowired
    private MediaMetadataMapper mediaMetadataMapper;

    @Autowired
    private MediaDirectoryMapper mediaDirectoryMapper;

    /**
     * 剧详情返回季卡片：季号升序、未知季排最后，含集数与观看进度标记。
     */
    @Test
    void shouldReturnSeriesDetailWithSeasons() {
        MediaSeries series = insertSeries("user-1", "dir-1", "测试剧");
        MediaSeason season1 = insertSeason(series, 1);
        MediaSeason season2 = insertSeason(series, 2);
        MediaSeason unknownSeason = insertSeason(series, null);
        insertEpisode(series, season1, 1, 0L);
        insertEpisode(series, season1, 2, 0L);
        insertEpisode(series, season2, 1, 100L);

        MediaSeriesDetailVo detail = mediaItemService.getSeriesDetail(series.getId(), "user-1");

        assertEquals("测试剧", detail.getSeriesName());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), detail.getMatchStatus());
        assertEquals(3, detail.getSeasons().size());
        assertEquals(1, detail.getSeasons().get(0).getSeasonNo());
        assertEquals(2, detail.getSeasons().get(1).getSeasonNo());
        assertNull(detail.getSeasons().get(2).getSeasonNo());
        assertEquals(unknownSeason.getId(), detail.getSeasons().get(2).getSeasonId());
        assertEquals(2, detail.getSeasons().get(0).getEpisodeCount());
        assertFalse(detail.getSeasons().get(0).getHasProgress());
        assertTrue(detail.getSeasons().get(1).getHasProgress());
    }

    /**
     * 按季查询剧集：只返回该季的集并按集号升序；季不属于该剧或用户越权时抛业务异常。
     */
    @Test
    void shouldListSeasonEpisodesOrderedByEpisodeNo() {
        MediaSeries series = insertSeries("user-1", "dir-1", "测试剧");
        MediaSeason season1 = insertSeason(series, 1);
        MediaSeason season2 = insertSeason(series, 2);
        insertEpisode(series, season1, 2, 0L);
        insertEpisode(series, season1, 1, 0L);
        insertEpisode(series, season2, 1, 0L);

        List<MediaItemVo> episodes = mediaItemService.listSeasonEpisodes(series.getId(), season1.getId(), "user-1");

        assertEquals(2, episodes.size());
        assertEquals(1, episodes.get(0).getEpisodeNo());
        assertEquals(2, episodes.get(1).getEpisodeNo());
        assertThrows(BusinessException.class,
                () -> mediaItemService.listSeasonEpisodes(series.getId(), season2.getId(), "user-2"));
        assertThrows(BusinessException.class,
                () -> mediaItemService.listSeasonEpisodes(series.getId(), "not-exists", "user-1"));
    }

    @Test
    void shouldThrowWhenSeriesNotFound() {
        assertThrows(BusinessException.class,
                () -> mediaItemService.getSeriesDetail("not-exists", "user-1"));
    }

    @Test
    void shouldThrowWhenSeriesNotOwned() {
        MediaSeries series = insertSeries("user-1", "dir-1", "测试剧");

        assertThrows(BusinessException.class,
                () -> mediaItemService.getSeriesDetail(series.getId(), "user-2"));
    }

    /**
     * 电影海报墙按媒体库过滤（新模型 t_media_movie，issue #18）。
     */
    @Test
    void shouldFilterMoviesByDirectoryId() {
        insertMovie("user-1", "dir-1");
        insertMovie("user-1", "dir-2");

        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setDirectoryId("dir-1");
        assertEquals(1L, mediaItemService.listMovies("user-1", query).getTotal());
        assertEquals(2L, mediaItemService.listMovies("user-1", new MediaPageQueryDto()).getTotal());
    }

    /**
     * 电视剧海报墙按媒体库过滤（剧行直接归属媒体库）。
     */
    @Test
    void shouldFilterSeriesByDirectoryId() {
        insertSeries("user-1", "dir-1", "剧一");
        insertSeries("user-1", "dir-2", "剧二");

        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setDirectoryId("dir-1");
        IPage<MediaSeriesVo> page = mediaItemService.listSeries("user-1", query);

        assertEquals(1L, page.getTotal());
        assertEquals("剧一", page.getRecords().getFirst().getSeriesName());
        assertEquals(2L, mediaItemService.listSeries("user-1", new MediaPageQueryDto()).getTotal());
    }

    /**
     * 电影详情版本列表确定性与默认版本（issue #21 收尾）：
     * 无 last_play_file_id 时 defaultVersionId 指向最早版本且 versions 按 create_time 升序；
     * 有 last_play_file_id 且明细仍存在时 defaultVersionId 指向该版本。
     */
    @Test
    void shouldExposeDefaultVersionIdInMovieDetail() {
        MediaMovie movie = insertMovieWithVersions("user-1", "dir-1");

        MediaItemDetailVo detail = mediaItemService.getItemDetail(movie.getId(), "user-1");
        assertEquals("ver-v1", detail.getDefaultVersionId());
        assertEquals(List.of("ver-v1", "ver-v2"),
                detail.getVersions().stream().map(MediaMovieVersionVo::getId).toList());

        // 续播定位 v2 后默认版本随之指向 v2
        movie.setLastPlayFileId("ver-v2");
        mediaMovieMapper.updateById(movie);
        MediaItemDetailVo resumed = mediaItemService.getItemDetail(movie.getId(), "user-1");
        assertEquals("ver-v2", resumed.getDefaultVersionId());
        assertEquals(List.of("ver-v1", "ver-v2"),
                resumed.getVersions().stream().map(MediaMovieVersionVo::getId).toList());
    }

    /**
     * 全局搜索：跨库按电影/剧集/其他分组，关键词分别命中电影标题/元数据标题/原始标题/简介/剧名/其他条目名；
     * 用户隔离（user-2 查不到）。
     */
    @Test
    void shouldSearchGloballyGroupedAndIsolated() {
        // 电影标题命中
        insertMovie("user-1", "dir-1", "星际穿越");
        // 元数据标题 / 原始标题 / 简介命中（同一部电影）
        MediaMovie matrix = insertMovie("user-1", "dir-2", "无关电影");
        MediaMetadata matrixMd = insertMetadata("user-1", MediaMetadataOwnerType.MOVIE.getCode(), matrix.getId());
        matrixMd.setTitle("黑客帝国");
        matrixMd.setOriginalTitle("the matrix");
        matrixMd.setOverview("讲述星际航行与数字世界的电影");
        mediaMetadataMapper.updateById(matrixMd);
        matrix.setMetadataId(matrixMd.getId());
        mediaMovieMapper.updateById(matrix);
        // 剧名命中
        insertSeries("user-1", "dir-1", "疑犯追踪");
        // 其他条目名命中
        insertOther("user-1", "dir-2", "星际纪录片.mp4");

        MediaSearchResultVo byMovieTitle = mediaItemService.search("user-1", "星际穿越", 8);
        assertEquals(1L, byMovieTitle.getMovies().getTotal());
        assertEquals(0L, byMovieTitle.getSeries().getTotal());
        assertEquals(0L, byMovieTitle.getOthers().getTotal());

        MediaSearchResultVo byMetadataTitle = mediaItemService.search("user-1", "黑客帝国", 8);
        assertEquals(1L, byMetadataTitle.getMovies().getTotal());

        MediaSearchResultVo byOriginalTitle = mediaItemService.search("user-1", "the matrix", 8);
        assertEquals(1L, byOriginalTitle.getMovies().getTotal());

        MediaSearchResultVo byOverview = mediaItemService.search("user-1", "数字世界", 8);
        assertEquals(1L, byOverview.getMovies().getTotal());

        MediaSearchResultVo bySeriesName = mediaItemService.search("user-1", "疑犯", 8);
        assertEquals(1L, bySeriesName.getSeries().getTotal());
        assertEquals("疑犯追踪", bySeriesName.getSeries().getRecords().getFirst().getSeriesName());

        MediaSearchResultVo byOtherName = mediaItemService.search("user-1", "纪录片", 8);
        assertEquals(1L, byOtherName.getOthers().getTotal());

        // 跨库：一个关键词同时命中两个库的电影（电影标题 + 元数据简介）与其他条目
        MediaSearchResultVo crossLibrary = mediaItemService.search("user-1", "星际", 8);
        assertEquals(2L, crossLibrary.getMovies().getTotal());
        assertEquals(1L, crossLibrary.getOthers().getTotal());

        // 用户隔离：user-2 查不到任何分组
        MediaSearchResultVo foreign = mediaItemService.search("user-2", "星际", 8);
        assertEquals(0L, foreign.getMovies().getTotal());
        assertEquals(0L, foreign.getSeries().getTotal());
        assertEquals(0L, foreign.getOthers().getTotal());
    }

    /**
     * 全局搜索 size 截断：records 长度 = size，total 为真实命中数。
     */
    @Test
    void shouldSearchTruncateRecordsBySize() {
        insertMovie("user-1", "dir-1", "星际A");
        insertMovie("user-1", "dir-1", "星际B");
        insertMovie("user-1", "dir-1", "星际C");

        MediaSearchResultVo result = mediaItemService.search("user-1", "星际", 2);

        assertEquals(3L, result.getMovies().getTotal());
        assertEquals(2, result.getMovies().getRecords().size());
        assertEquals(2L, result.getMovies().getSize());
    }

    /**
     * 电影按评分排序：vote_average 为空的电影始终沉底（NULLS LAST），升降序均验证。
     */
    @Test
    void shouldSortMoviesByRatingNullsLast() {
        MediaMovie high = insertMovie("user-1", "dir-1", "高分电影");
        bindRating(high, 9.0);
        MediaMovie none = insertMovie("user-1", "dir-1", "无评分电影");
        bindRating(none, null);
        MediaMovie mid = insertMovie("user-1", "dir-1", "中分电影");
        bindRating(mid, 7.0);

        MediaPageQueryDto descQuery = new MediaPageQueryDto();
        descQuery.setSortField(MediaPageQueryDto.SORT_FIELD_RATING);
        assertEquals(List.of(high.getId(), mid.getId(), none.getId()),
                idsOf(mediaItemService.listMovies("user-1", descQuery).getRecords()));

        MediaPageQueryDto ascQuery = new MediaPageQueryDto();
        ascQuery.setSortField(MediaPageQueryDto.SORT_FIELD_RATING);
        ascQuery.setSortOrder("asc");
        assertEquals(List.of(mid.getId(), high.getId(), none.getId()),
                idsOf(mediaItemService.listMovies("user-1", ascQuery).getRecords()));
    }

    /**
     * 电影按标题排序：元数据标题优先、无元数据回退电影标题（COALESCE 混排）。
     */
    @Test
    void shouldSortMoviesByTitleWithMetadataFallback() {
        MediaMovie metaFirst = insertMovie("user-1", "dir-1", "movie-z");
        bindTitle(metaFirst, "movie-a");
        MediaMovie fallback = insertMovie("user-1", "dir-1", "movie-b");
        MediaMovie metaLast = insertMovie("user-1", "dir-1", "movie-c");
        bindTitle(metaLast, "movie-d");

        MediaPageQueryDto ascQuery = new MediaPageQueryDto();
        ascQuery.setSortField(MediaPageQueryDto.SORT_FIELD_TITLE);
        ascQuery.setSortOrder("asc");
        assertEquals(List.of(metaFirst.getId(), fallback.getId(), metaLast.getId()),
                idsOf(mediaItemService.listMovies("user-1", ascQuery).getRecords()));

        MediaPageQueryDto descQuery = new MediaPageQueryDto();
        descQuery.setSortField(MediaPageQueryDto.SORT_FIELD_TITLE);
        assertEquals(List.of(metaLast.getId(), fallback.getId(), metaFirst.getId()),
                idsOf(mediaItemService.listMovies("user-1", descQuery).getRecords()));
    }

    /**
     * 剧集按标题排序：元数据标题优先、无元数据回退剧名（COALESCE 混排）。
     */
    @Test
    void shouldSortSeriesByTitleWithMetadataFallback() {
        MediaSeries metaFirst = insertSeries("user-1", "dir-1", "series-z");
        bindSeriesTitle(metaFirst, "series-a");
        MediaSeries fallback = insertSeries("user-1", "dir-1", "series-b");

        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setSortField(MediaPageQueryDto.SORT_FIELD_TITLE);
        query.setSortOrder("asc");
        List<MediaSeriesVo> asc = mediaItemService.listSeries("user-1", query).getRecords();

        assertEquals(List.of(metaFirst.getId(), fallback.getId()),
                asc.stream().map(MediaSeriesVo::getId).toList());
    }

    /**
     * 其他库按标题（条目名）排序；release/rating 对其不生效，回落添加时间不报错。
     */
    @Test
    void shouldSortOthersByTitleAndIgnoreUnsupportedFields() {
        insertOther("user-1", "dir-1", "apple.mp4");
        insertOther("user-1", "dir-1", "banana.mp4");
        insertOther("user-1", "dir-1", "cherry.mp4");

        MediaPageQueryDto ascQuery = new MediaPageQueryDto();
        ascQuery.setSortField(MediaPageQueryDto.SORT_FIELD_TITLE);
        ascQuery.setSortOrder("asc");
        assertEquals(List.of("apple.mp4", "banana.mp4", "cherry.mp4"),
                mediaItemService.listOthers("user-1", ascQuery).getRecords().stream()
                        .map(MediaItemVo::getTitle).toList());

        MediaPageQueryDto descQuery = new MediaPageQueryDto();
        descQuery.setSortField(MediaPageQueryDto.SORT_FIELD_TITLE);
        assertEquals(List.of("cherry.mp4", "banana.mp4", "apple.mp4"),
                mediaItemService.listOthers("user-1", descQuery).getRecords().stream()
                        .map(MediaItemVo::getTitle).toList());

        // release/rating 对其他库不生效：回落添加时间，不报错且全部返回
        for (String unsupported : List.of(MediaPageQueryDto.SORT_FIELD_RELEASE, MediaPageQueryDto.SORT_FIELD_RATING)) {
            MediaPageQueryDto query = new MediaPageQueryDto();
            query.setSortField(unsupported);
            assertEquals(3L, mediaItemService.listOthers("user-1", query).getTotal());
        }
    }

    /**
     * 类型聚合（电影库）：元数据 genres 逗号拆分计数、条目数降序（同数按名称升序）、
     * 代表海报取自首个有海报条目的元数据；无元数据行不计入。
     */
    @Test
    void shouldAggregateMovieGenres() {
        MediaDirectory directory = insertDirectory("user-1", MediaType.MOVIE.getCode());
        MediaMovie actionSciFi = insertMovie("user-1", directory.getId(), "电影A");
        bindMovieGenres(actionSciFi, "动作,科幻", "poster-1");
        MediaMovie action = insertMovie("user-1", directory.getId(), "电影B");
        bindMovieGenres(action, "动作", "poster-2");
        insertMovie("user-1", directory.getId(), "电影C"); // 无元数据行，不计入

        List<MediaGenreVo> genres = mediaItemService.listGenres("user-1", directory.getId());

        assertEquals(List.of("动作", "科幻"), genres.stream().map(MediaGenreVo::getName).toList());
        assertEquals(List.of(2L, 1L), genres.stream().map(MediaGenreVo::getItemCount).toList());
        // 代表海报非空：取该类型下首个有海报条目的元数据海报地址
        assertTrue(genres.stream().allMatch(g -> g.getPosterUrl() != null && g.getPosterUrl().endsWith("/poster")));
    }

    /**
     * 类型聚合（剧集库）：走剧集行元数据，同理拆分计数。
     */
    @Test
    void shouldAggregateSeriesGenres() {
        MediaDirectory directory = insertDirectory("user-1", MediaType.TV.getCode());
        MediaSeries sciFi = insertSeries("user-1", directory.getId(), "剧一");
        bindSeriesGenres(sciFi, "科幻", "poster-1");
        MediaSeries thriller = insertSeries("user-1", directory.getId(), "剧二");
        bindSeriesGenres(thriller, "科幻,悬疑", "poster-2");

        List<MediaGenreVo> genres = mediaItemService.listGenres("user-1", directory.getId());

        assertEquals(List.of("科幻", "悬疑"), genres.stream().map(MediaGenreVo::getName).toList());
        assertEquals(2L, genres.get(0).getItemCount());
        assertEquals(1L, genres.get(1).getItemCount());
        assertTrue(genres.get(0).getPosterUrl() != null && genres.get(0).getPosterUrl().endsWith("/poster"));
    }

    /**
     * 其他库无类型概念：返回空列表。
     */
    @Test
    void shouldReturnEmptyGenresForOtherDirectory() {
        MediaDirectory directory = insertDirectory("user-1", MediaType.OTHER.getCode());
        insertOther("user-1", directory.getId(), "记录片.mp4");

        assertTrue(mediaItemService.listGenres("user-1", directory.getId()).isEmpty());
    }

    /**
     * 类型聚合越权：目录不属于该用户或不存在时抛业务异常。
     */
    @Test
    void shouldThrowWhenGenresDirectoryNotOwnedOrMissing() {
        MediaDirectory directory = insertDirectory("user-1", MediaType.MOVIE.getCode());

        assertThrows(BusinessException.class,
                () -> mediaItemService.listGenres("user-2", directory.getId()));
        assertThrows(BusinessException.class,
                () -> mediaItemService.listGenres("user-1", "not-exists"));
    }

    /**
     * 电影墙 genre 筛选：精确匹配元数据 genres 拆分后的值，「动作片」这类子串不误中。
     */
    @Test
    void shouldFilterMoviesByGenreExactMatch() {
        MediaDirectory directory = insertDirectory("user-1", MediaType.MOVIE.getCode());
        MediaMovie action = insertMovie("user-1", directory.getId(), "动作电影");
        bindMovieGenres(action, "动作", null);
        MediaMovie actionSubstring = insertMovie("user-1", directory.getId(), "动作片");
        bindMovieGenres(actionSubstring, "动作片", null);
        MediaMovie drama = insertMovie("user-1", directory.getId(), "剧情片");
        bindMovieGenres(drama, "剧情", null);

        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setDirectoryId(directory.getId());
        query.setGenre("动作");

        IPage<MediaItemVo> page = mediaItemService.listMovies("user-1", query);

        assertEquals(1L, page.getTotal());
        assertEquals(action.getId(), page.getRecords().getFirst().getId());
    }

    /**
     * 电影墙 genre 与 keyword 叠加：两者同时收窄。
     */
    @Test
    void shouldCombineGenreWithKeywordFilter() {
        MediaDirectory directory = insertDirectory("user-1", MediaType.MOVIE.getCode());
        MediaMovie avatar = insertMovie("user-1", directory.getId(), "阿凡达");
        bindMovieGenres(avatar, "科幻", null);
        MediaMovie interstellar = insertMovie("user-1", directory.getId(), "星际穿越");
        bindMovieGenres(interstellar, "科幻", null);
        MediaMovie avatar2 = insertMovie("user-1", directory.getId(), "阿凡达2");
        bindMovieGenres(avatar2, "动作", null);

        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setDirectoryId(directory.getId());
        query.setGenre("科幻");
        query.setKeyword("阿凡达");

        IPage<MediaItemVo> page = mediaItemService.listMovies("user-1", query);

        assertEquals(1L, page.getTotal());
        assertEquals(avatar.getId(), page.getRecords().getFirst().getId());
    }

    /**
     * 电影墙 genre 与 sortField=title 叠加：仅筛选出的条目参与排序。
     */
    @Test
    void shouldCombineGenreWithTitleSort() {
        MediaDirectory directory = insertDirectory("user-1", MediaType.MOVIE.getCode());
        MediaMovie zeta = insertMovie("user-1", directory.getId(), "zeta");
        bindMovieGenres(zeta, "科幻", null);
        MediaMovie alpha = insertMovie("user-1", directory.getId(), "alpha");
        bindMovieGenres(alpha, "科幻", null);
        MediaMovie gamma = insertMovie("user-1", directory.getId(), "gamma");
        bindMovieGenres(gamma, "动作", null);

        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setDirectoryId(directory.getId());
        query.setGenre("科幻");
        query.setSortField(MediaPageQueryDto.SORT_FIELD_TITLE);
        query.setSortOrder("asc");

        IPage<MediaItemVo> page = mediaItemService.listMovies("user-1", query);

        assertEquals(2L, page.getTotal());
        assertEquals(List.of(alpha.getId(), zeta.getId()), idsOf(page.getRecords()));
    }

    /**
     * 剧集墙 genre 筛选：精确匹配剧集行元数据 genres。
     */
    @Test
    void shouldFilterSeriesByGenre() {
        MediaDirectory directory = insertDirectory("user-1", MediaType.TV.getCode());
        MediaSeries sciFi = insertSeries("user-1", directory.getId(), "科幻剧");
        bindSeriesGenres(sciFi, "科幻", null);
        MediaSeries thriller = insertSeries("user-1", directory.getId(), "悬疑剧");
        bindSeriesGenres(thriller, "悬疑", null);

        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setDirectoryId(directory.getId());
        query.setGenre("科幻");

        IPage<MediaSeriesVo> page = mediaItemService.listSeries("user-1", query);

        assertEquals(1L, page.getTotal());
        assertEquals(sciFi.getId(), page.getRecords().getFirst().getId());
    }

    /**
     * NFO 来源类型串可能含空格（如 "动作, 冒险 "）：筛选与聚合两侧均需逐元素 trim，
     * 保证类型卡片与筛选结果一致。
     */
    @Test
    void shouldTrimGenreElementsInFilterAndAggregation() {
        MediaDirectory directory = insertDirectory("user-1", MediaType.MOVIE.getCode());
        MediaMovie spaced = insertMovie("user-1", directory.getId(), "带空格类型电影");
        bindMovieGenres(spaced, " 动作, 冒险 ", "poster-trim");

        MediaPageQueryDto filter = new MediaPageQueryDto();
        filter.setDirectoryId(directory.getId());
        filter.setGenre("冒险");
        assertEquals(1L, mediaItemService.listMovies("user-1", filter).getTotal());

        filter.setGenre("动作");
        assertEquals(1L, mediaItemService.listMovies("user-1", filter).getTotal());

        List<MediaGenreVo> genres = mediaItemService.listGenres("user-1", directory.getId());
        assertEquals(2, genres.size());
        // 同条目数按名称升序（Java 码位序：冒险 < 动作）
        assertEquals(List.of("冒险", "动作"), genres.stream().map(MediaGenreVo::getName).toList());
        assertEquals(1L, genres.getFirst().getItemCount());
    }

    private MediaDirectory insertDirectory(String userId, String mediaType) {
        MediaDirectory directory = new MediaDirectory();
        directory.setUserId(userId);
        directory.setName("测试媒体库");
        directory.setMediaType(mediaType);
        mediaDirectoryMapper.insert(directory);
        return directory;
    }

    private MediaSeries insertSeries(String userId, String directoryId, String seriesName) {
        MediaSeries series = new MediaSeries();
        series.setUserId(userId);
        series.setDirectoryId(directoryId);
        series.setFolderNodeId(IdUtil.nextId());
        series.setSeriesName(seriesName);
        series.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
        mediaSeriesMapper.insert(series);
        return series;
    }

    private MediaSeason insertSeason(MediaSeries series, Integer seasonNo) {
        MediaSeason season = new MediaSeason();
        season.setSeriesId(series.getId());
        season.setFolderNodeId(IdUtil.nextId());
        season.setSeasonNo(seasonNo);
        mediaSeasonMapper.insert(season);
        return season;
    }

    private void insertEpisode(MediaSeries series, MediaSeason season, int episodeNo, long progressMs) {
        MediaEpisode episode = new MediaEpisode();
        episode.setSeriesId(series.getId());
        episode.setSeasonId(season.getId());
        episode.setEpisodeNo(episodeNo);
        episode.setProgressMs(progressMs);
        mediaEpisodeMapper.insert(episode);
    }

    private void insertMovie(String userId, String directoryId) {
        insertMovie(userId, directoryId, "测试电影");
    }

    private MediaMovie insertMovie(String userId, String directoryId, String title) {
        MediaMovie movie = new MediaMovie();
        movie.setUserId(userId);
        movie.setDirectoryId(directoryId);
        movie.setFolderNodeId(IdUtil.nextId());
        movie.setTitle(title);
        movie.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
        movie.setMetadataComplete(false);
        mediaMovieMapper.insert(movie);
        return movie;
    }

    /**
     * 插入带两个版本明细（create_time 依次 v1 早 / v2 晚）的电影，验证版本列表顺序与默认版本。
     */
    private MediaMovie insertMovieWithVersions(String userId, String directoryId) {
        MediaMovie movie = new MediaMovie();
        movie.setUserId(userId);
        movie.setDirectoryId(directoryId);
        movie.setFolderNodeId(IdUtil.nextId());
        movie.setTitle("测试电影");
        movie.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
        movie.setMetadataComplete(false);
        mediaMovieMapper.insert(movie);
        insertVersion(movie.getId(), "ver-v1", java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        insertVersion(movie.getId(), "ver-v2", java.time.LocalDateTime.of(2026, 1, 2, 0, 0));
        return movie;
    }

    private void insertVersion(String movieId, String versionId, java.time.LocalDateTime createTime) {
        MediaMovieFile file = new MediaMovieFile();
        file.setId(versionId);
        file.setMovieId(movieId);
        file.setFileNodeId(IdUtil.nextId());
        file.setCreateTime(createTime);
        mediaMovieFileMapper.insert(file);
    }

    private MediaOther insertOther(String userId, String directoryId, String name) {
        MediaOther other = new MediaOther();
        other.setUserId(userId);
        other.setDirectoryId(directoryId);
        other.setFileNodeId(IdUtil.nextId());
        other.setName(name);
        mediaOtherMapper.insert(other);
        return other;
    }

    private MediaMetadata insertMetadata(String userId, String ownerType, String ownerId) {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setUserId(userId);
        metadata.setOwnerType(ownerType);
        metadata.setOwnerId(ownerId);
        metadata.setSource(MediaMetadataSource.TMDB.getCode());
        metadata.setPersistStatus(MediaPersistStatus.PERSISTED.getCode());
        mediaMetadataMapper.insert(metadata);
        return metadata;
    }

    /** 给电影绑定指定评分的元数据。 */
    private void bindRating(MediaMovie movie, Double voteAverage) {
        MediaMetadata metadata = insertMetadata(movie.getUserId(), MediaMetadataOwnerType.MOVIE.getCode(), movie.getId());
        metadata.setVoteAverage(voteAverage);
        mediaMetadataMapper.updateById(metadata);
        movie.setMetadataId(metadata.getId());
        mediaMovieMapper.updateById(movie);
    }

    /** 给电影绑定指定标题的元数据。 */
    private void bindTitle(MediaMovie movie, String title) {
        MediaMetadata metadata = insertMetadata(movie.getUserId(), MediaMetadataOwnerType.MOVIE.getCode(), movie.getId());
        metadata.setTitle(title);
        mediaMetadataMapper.updateById(metadata);
        movie.setMetadataId(metadata.getId());
        mediaMovieMapper.updateById(movie);
    }

    /** 给剧集绑定指定标题的元数据。 */
    private void bindSeriesTitle(MediaSeries series, String title) {
        MediaMetadata metadata = insertMetadata(series.getUserId(), MediaMetadataOwnerType.SERIES.getCode(), series.getId());
        metadata.setTitle(title);
        mediaMetadataMapper.updateById(metadata);
        series.setMetadataId(metadata.getId());
        mediaSeriesMapper.updateById(series);
    }

    /** 给电影绑定指定 genres 的元数据（posterFileNodeId 非空时代表海报可被聚合命中）。 */
    private void bindMovieGenres(MediaMovie movie, String genres, String posterFileNodeId) {
        MediaMetadata metadata = insertMetadata(movie.getUserId(), MediaMetadataOwnerType.MOVIE.getCode(), movie.getId());
        metadata.setGenres(genres);
        metadata.setPosterFileNodeId(posterFileNodeId);
        mediaMetadataMapper.updateById(metadata);
        movie.setMetadataId(metadata.getId());
        mediaMovieMapper.updateById(movie);
    }

    /** 给剧集绑定指定 genres 的元数据（posterFileNodeId 非空时代表海报可被聚合命中）。 */
    private void bindSeriesGenres(MediaSeries series, String genres, String posterFileNodeId) {
        MediaMetadata metadata = insertMetadata(series.getUserId(), MediaMetadataOwnerType.SERIES.getCode(), series.getId());
        metadata.setGenres(genres);
        metadata.setPosterFileNodeId(posterFileNodeId);
        mediaMetadataMapper.updateById(metadata);
        series.setMetadataId(metadata.getId());
        mediaSeriesMapper.updateById(series);
    }

    private List<String> idsOf(List<MediaItemVo> vos) {
        return vos.stream().map(MediaItemVo::getId).toList();
    }
}
