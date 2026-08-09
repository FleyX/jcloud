package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.common.enums.MediaMetadataSource;
import com.fleyx.jcloud.common.enums.MediaPersistStatus;
import com.fleyx.jcloud.common.enums.MediaType;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.po.FileNode;
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

import java.time.LocalDateTime;
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

    @Autowired
    private FileMapper fileMapper;

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

    // ---------- 票据 02：海报墙查询矩阵（分页/关键词/排序） ----------

    /**
     * 电影墙分页：滚动加载下一页，pageNum/pageSize 语义正确，total 为真实命中数且无重复。
     */
    @Test
    void shouldPaginateMoviesAcrossPages() {
        insertMovieAt("user-1", "dir-1", "电影A", LocalDateTime.of(2026, 1, 1, 0, 0));
        insertMovieAt("user-1", "dir-1", "电影B", LocalDateTime.of(2026, 1, 1, 0, 1));
        insertMovieAt("user-1", "dir-1", "电影C", LocalDateTime.of(2026, 1, 1, 0, 2));

        MediaPageQueryDto page1 = new MediaPageQueryDto();
        page1.setPageSize(2L);
        IPage<MediaItemVo> first = mediaItemService.listMovies("user-1", page1);
        assertEquals(3L, first.getTotal());
        assertEquals(List.of("电影C", "电影B"), first.getRecords().stream().map(MediaItemVo::getTitle).toList());

        MediaPageQueryDto page2 = new MediaPageQueryDto();
        page2.setPageNum(2L);
        page2.setPageSize(2L);
        IPage<MediaItemVo> second = mediaItemService.listMovies("user-1", page2);
        assertEquals(3L, second.getTotal());
        assertEquals(List.of("电影A"), second.getRecords().stream().map(MediaItemVo::getTitle).toList());
    }

    /**
     * 剧集墙分页：同上，滚动加载下一页。
     */
    @Test
    void shouldPaginateSeriesAcrossPages() {
        insertSeriesAt("user-1", "dir-1", "剧一", 100L);
        insertSeriesAt("user-1", "dir-1", "剧二", 200L);
        insertSeriesAt("user-1", "dir-1", "剧三", 300L);

        MediaPageQueryDto page1 = new MediaPageQueryDto();
        page1.setPageSize(2L);
        IPage<MediaSeriesVo> first = mediaItemService.listSeries("user-1", page1);
        assertEquals(3L, first.getTotal());
        assertEquals(List.of("剧三", "剧二"), first.getRecords().stream().map(MediaSeriesVo::getSeriesName).toList());

        MediaPageQueryDto page2 = new MediaPageQueryDto();
        page2.setPageNum(2L);
        page2.setPageSize(2L);
        IPage<MediaSeriesVo> second = mediaItemService.listSeries("user-1", page2);
        assertEquals(3L, second.getTotal());
        assertEquals(List.of("剧一"), second.getRecords().stream().map(MediaSeriesVo::getSeriesName).toList());
    }

    /**
     * 其他网格分页：同上，滚动加载下一页。
     */
    @Test
    void shouldPaginateOthersAcrossPages() {
        insertOtherAt("user-1", "dir-1", "a.mp4", LocalDateTime.of(2026, 1, 1, 0, 0));
        insertOtherAt("user-1", "dir-1", "b.mp4", LocalDateTime.of(2026, 1, 1, 0, 1));
        insertOtherAt("user-1", "dir-1", "c.mp4", LocalDateTime.of(2026, 1, 1, 0, 2));

        MediaPageQueryDto page1 = new MediaPageQueryDto();
        page1.setPageSize(2L);
        IPage<MediaItemVo> first = mediaItemService.listOthers("user-1", page1);
        assertEquals(3L, first.getTotal());
        assertEquals(List.of("c.mp4", "b.mp4"), first.getRecords().stream().map(MediaItemVo::getTitle).toList());

        MediaPageQueryDto page2 = new MediaPageQueryDto();
        page2.setPageNum(2L);
        page2.setPageSize(2L);
        IPage<MediaItemVo> second = mediaItemService.listOthers("user-1", page2);
        assertEquals(3L, second.getTotal());
        assertEquals(List.of("a.mp4"), second.getRecords().stream().map(MediaItemVo::getTitle).toList());
    }

    /**
     * 电影墙关键词：命中电影标题 / 元数据标题 / 简介；不命中即过滤。
     */
    @Test
    void shouldSearchMoviesByKeywordInWall() {
        insertMovie("user-1", "dir-1", "星际穿越");
        MediaMovie matrix = insertMovie("user-1", "dir-2", "无关电影");
        MediaMetadata matrixMd = insertMetadata("user-1", MediaMetadataOwnerType.MOVIE.getCode(), matrix.getId());
        matrixMd.setTitle("黑客帝国");
        matrixMd.setOverview("讲述数字世界与矩阵的电影");
        mediaMetadataMapper.updateById(matrixMd);
        matrix.setMetadataId(matrixMd.getId());
        mediaMovieMapper.updateById(matrix);
        insertMovie("user-1", "dir-1", "纪录片集锦");

        assertEquals(1L, moviesWithKeyword("星际").getTotal());    // 电影标题命中
        assertEquals(1L, moviesWithKeyword("黑客帝国").getTotal()); // 元数据标题命中
        assertEquals(1L, moviesWithKeyword("数字世界").getTotal()); // 简介命中
        assertEquals(0L, moviesWithKeyword("不存在的词").getTotal());
    }

    /**
     * 剧集墙关键词：命中剧名 / 元数据标题 / 简介；不命中即过滤。
     */
    @Test
    void shouldSearchSeriesByKeywordInWall() {
        insertSeries("user-1", "dir-1", "疑犯追踪");
        MediaSeries lost = insertSeries("user-1", "dir-2", "迷失");
        MediaMetadata lostMd = insertMetadata("user-1", MediaMetadataOwnerType.SERIES.getCode(), lost.getId());
        lostMd.setTitle("迷失剧集");
        lostMd.setOverview("讲述荒岛生存的故事");
        mediaMetadataMapper.updateById(lostMd);
        lost.setMetadataId(lostMd.getId());
        mediaSeriesMapper.updateById(lost);

        assertEquals(1L, seriesWithKeyword("疑犯").getTotal());   // 剧名命中
        assertEquals(1L, seriesWithKeyword("迷失剧").getTotal());  // 元数据标题命中
        assertEquals(1L, seriesWithKeyword("荒岛").getTotal());    // 简介命中
        assertEquals(0L, seriesWithKeyword("不存在的词").getTotal());
    }

    /**
     * 其他网格关键词：命中条目名（文件名）。
     */
    @Test
    void shouldSearchOthersByKeywordInWall() {
        insertOther("user-1", "dir-1", "星际纪录片.mp4");
        insertOther("user-1", "dir-2", "教学视频.01.mp4");

        assertEquals(1L, othersWithKeyword("纪录片").getTotal());
        assertEquals(1L, othersWithKeyword("教学").getTotal());
        assertEquals(0L, othersWithKeyword("不存在的词").getTotal());
    }

    /**
     * 电影墙排序（添加时间）：默认添加时间倒序；显式 asc 升序；发行日期为空的电影不参与。
     */
    @Test
    void shouldSortMoviesByAddedTimeAscDescWithDefault() {
        MediaMovie oldest = insertMovieAt("user-1", "dir-1", "最早", LocalDateTime.of(2026, 1, 1, 0, 0));
        MediaMovie middle = insertMovieAt("user-1", "dir-1", "中间", LocalDateTime.of(2026, 1, 1, 0, 1));
        MediaMovie latest = insertMovieAt("user-1", "dir-1", "最新", LocalDateTime.of(2026, 1, 1, 0, 2));

        // 默认：添加时间倒序
        assertEquals(List.of(latest.getId(), middle.getId(), oldest.getId()),
                idsOf(mediaItemService.listMovies("user-1", new MediaPageQueryDto()).getRecords()));

        // 显式 asc（sortField=added）
        MediaPageQueryDto asc = new MediaPageQueryDto();
        asc.setSortField(MediaPageQueryDto.SORT_FIELD_ADDED);
        asc.setSortOrder("asc");
        assertEquals(List.of(oldest.getId(), middle.getId(), latest.getId()),
                idsOf(mediaItemService.listMovies("user-1", asc).getRecords()));
    }

    /**
     * 剧集墙排序（添加时间 = min_file_last_modified）：默认倒序；显式 asc 升序。
     */
    @Test
    void shouldSortSeriesByAddedTimeAscDescWithDefault() {
        MediaSeries oldest = insertSeriesAt("user-1", "dir-1", "剧一", 100L);
        MediaSeries middle = insertSeriesAt("user-1", "dir-1", "剧二", 200L);
        MediaSeries latest = insertSeriesAt("user-1", "dir-1", "剧三", 300L);

        assertEquals(List.of(latest.getId(), middle.getId(), oldest.getId()),
                mediaItemService.listSeries("user-1", new MediaPageQueryDto()).getRecords().stream()
                        .map(MediaSeriesVo::getId).toList());

        MediaPageQueryDto asc = new MediaPageQueryDto();
        asc.setSortField(MediaPageQueryDto.SORT_FIELD_ADDED);
        asc.setSortOrder("asc");
        assertEquals(List.of(oldest.getId(), middle.getId(), latest.getId()),
                mediaItemService.listSeries("user-1", asc).getRecords().stream()
                        .map(MediaSeriesVo::getId).toList());
    }

    /**
     * 其他网格排序（添加时间 = create_time）：默认倒序；显式 asc 升序。
     */
    @Test
    void shouldSortOthersByAddedTimeAscDescWithDefault() {
        insertOtherAt("user-1", "dir-1", "a.mp4", LocalDateTime.of(2026, 1, 1, 0, 0));
        insertOtherAt("user-1", "dir-1", "b.mp4", LocalDateTime.of(2026, 1, 1, 0, 1));
        insertOtherAt("user-1", "dir-1", "c.mp4", LocalDateTime.of(2026, 1, 1, 0, 2));

        assertEquals(List.of("c.mp4", "b.mp4", "a.mp4"),
                mediaItemService.listOthers("user-1", new MediaPageQueryDto()).getRecords().stream()
                        .map(MediaItemVo::getTitle).toList());

        MediaPageQueryDto asc = new MediaPageQueryDto();
        asc.setSortField(MediaPageQueryDto.SORT_FIELD_ADDED);
        asc.setSortOrder("asc");
        assertEquals(List.of("a.mp4", "b.mp4", "c.mp4"),
                mediaItemService.listOthers("user-1", asc).getRecords().stream()
                        .map(MediaItemVo::getTitle).toList());
    }

    /**
     * 电影墙排序（发行时间）：升/降序均正确，发行日期为空的电影始终沉底（NULLS LAST）。
     */
    @Test
    void shouldSortMoviesByReleaseDateAscDescNullsLast() {
        MediaMovie oldest = insertMovie("user-1", "dir-1", "老片");
        bindReleaseDate(oldest, "2020-01-01");
        MediaMovie middle = insertMovie("user-1", "dir-1", "中片");
        bindReleaseDate(middle, "2021-01-01");
        MediaMovie newest = insertMovie("user-1", "dir-1", "新片");
        bindReleaseDate(newest, "2022-01-01");
        MediaMovie none = insertMovie("user-1", "dir-1", "无日期片");

        MediaPageQueryDto desc = new MediaPageQueryDto();
        desc.setSortField(MediaPageQueryDto.SORT_FIELD_RELEASE);
        assertEquals(List.of(newest.getId(), middle.getId(), oldest.getId(), none.getId()),
                idsOf(mediaItemService.listMovies("user-1", desc).getRecords()));

        MediaPageQueryDto asc = new MediaPageQueryDto();
        asc.setSortField(MediaPageQueryDto.SORT_FIELD_RELEASE);
        asc.setSortOrder("asc");
        assertEquals(List.of(oldest.getId(), middle.getId(), newest.getId(), none.getId()),
                idsOf(mediaItemService.listMovies("user-1", asc).getRecords()));
    }

    /**
     * 剧集墙排序（发行时间）：升/降序均正确，发行日期为空的剧始终沉底（NULLS LAST）。
     */
    @Test
    void shouldSortSeriesByReleaseDateAscDescNullsLast() {
        MediaSeries oldest = insertSeries("user-1", "dir-1", "老剧");
        bindSeriesReleaseDate(oldest, "2020-01-01");
        MediaSeries middle = insertSeries("user-1", "dir-1", "中剧");
        bindSeriesReleaseDate(middle, "2021-01-01");
        MediaSeries newest = insertSeries("user-1", "dir-1", "新剧");
        bindSeriesReleaseDate(newest, "2022-01-01");
        MediaSeries none = insertSeries("user-1", "dir-1", "无日期剧");

        MediaPageQueryDto desc = new MediaPageQueryDto();
        desc.setSortField(MediaPageQueryDto.SORT_FIELD_RELEASE);
        assertEquals(List.of(newest.getId(), middle.getId(), oldest.getId(), none.getId()),
                mediaItemService.listSeries("user-1", desc).getRecords().stream()
                        .map(MediaSeriesVo::getId).toList());

        MediaPageQueryDto asc = new MediaPageQueryDto();
        asc.setSortField(MediaPageQueryDto.SORT_FIELD_RELEASE);
        asc.setSortOrder("asc");
        assertEquals(List.of(oldest.getId(), middle.getId(), newest.getId(), none.getId()),
                mediaItemService.listSeries("user-1", asc).getRecords().stream()
                        .map(MediaSeriesVo::getId).toList());
    }

    // ---------- 工单 01：图片 URL 版本参数 ----------

    /**
     * 海报/背景指向真实 FileNode 时，URL 附带 ?v=<lastModified>（电影墙海报、详情海报与背景）。
     */
    @Test
    void shouldAppendVersionParamWhenPosterNodeExists() {
        MediaDirectory directory = insertDirectory("user-1", MediaType.MOVIE.getCode());
        MediaMovie movie = insertMovie("user-1", directory.getId(), "测试电影");
        FileNode poster = insertFileNode("user-1", 1_000L);
        FileNode backdrop = insertFileNode("user-1", 2_000L);
        MediaMetadata metadata = insertMetadata("user-1", MediaMetadataOwnerType.MOVIE.getCode(), movie.getId());
        metadata.setPosterFileNodeId(poster.getId());
        metadata.setBackdropFileNodeId(backdrop.getId());
        mediaMetadataMapper.updateById(metadata);
        movie.setMetadataId(metadata.getId());
        mediaMovieMapper.updateById(movie);

        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setDirectoryId(directory.getId());
        MediaItemVo vo = mediaItemService.listMovies("user-1", query).getRecords().getFirst();
        assertEquals("/jcloud/api/media/metadata/" + metadata.getId() + "/poster?v=1000", vo.getPosterUrl());

        MediaItemDetailVo detail = mediaItemService.getItemDetail(movie.getId(), "user-1");
        assertEquals("/jcloud/api/media/metadata/" + metadata.getId() + "/poster?v=1000", detail.getPosterUrl());
        assertEquals("/jcloud/api/media/metadata/" + metadata.getId() + "/backdrop?v=2000", detail.getBackdropUrl());
    }

    /**
     * 海报文件节点不存在（假 id/脏数据）时，URL 不带 ?v= 版本参数。
     */
    @Test
    void shouldOmitVersionParamWhenPosterNodeMissing() {
        MediaDirectory directory = insertDirectory("user-1", MediaType.MOVIE.getCode());
        MediaMovie movie = insertMovie("user-1", directory.getId(), "测试电影");
        MediaMetadata metadata = insertMetadata("user-1", MediaMetadataOwnerType.MOVIE.getCode(), movie.getId());
        metadata.setPosterFileNodeId(IdUtil.nextId()); // 无对应 t_file_node 行
        mediaMetadataMapper.updateById(metadata);
        movie.setMetadataId(metadata.getId());
        mediaMovieMapper.updateById(movie);

        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setDirectoryId(directory.getId());
        MediaItemVo vo = mediaItemService.listMovies("user-1", query).getRecords().getFirst();
        assertEquals("/jcloud/api/media/metadata/" + metadata.getId() + "/poster", vo.getPosterUrl());
    }

    /**
     * 海报文件被覆盖写（lastModified 变化）后，URL 版本参数随之更新。
     */
    @Test
    void shouldRefreshVersionParamWhenPosterNodeOverwritten() {
        MediaDirectory directory = insertDirectory("user-1", MediaType.MOVIE.getCode());
        MediaMovie movie = insertMovie("user-1", directory.getId(), "测试电影");
        FileNode poster = insertFileNode("user-1", 1_000L);
        MediaMetadata metadata = insertMetadata("user-1", MediaMetadataOwnerType.MOVIE.getCode(), movie.getId());
        metadata.setPosterFileNodeId(poster.getId());
        mediaMetadataMapper.updateById(metadata);
        movie.setMetadataId(metadata.getId());
        mediaMovieMapper.updateById(movie);
        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setDirectoryId(directory.getId());

        MediaItemVo first = mediaItemService.listMovies("user-1", query).getRecords().getFirst();
        assertEquals("/jcloud/api/media/metadata/" + metadata.getId() + "/poster?v=1000", first.getPosterUrl());

        // 覆盖写更新 lastModified
        FileNode update = new FileNode();
        update.setId(poster.getId());
        update.setLastModified(9_999L);
        fileMapper.updateById(update);

        MediaItemVo second = mediaItemService.listMovies("user-1", query).getRecords().getFirst();
        assertEquals("/jcloud/api/media/metadata/" + metadata.getId() + "/poster?v=9999", second.getPosterUrl());
    }

    /**
     * 季卡片与类型卡片海报指向真实 FileNode 时同样附带版本参数。
     */
    @Test
    void shouldAppendVersionParamForSeasonAndGenrePosters() {
        MediaDirectory movieDir = insertDirectory("user-1", MediaType.MOVIE.getCode());
        MediaMovie movie = insertMovie("user-1", movieDir.getId(), "类型电影");
        FileNode moviePoster = insertFileNode("user-1", 4_000L);
        MediaMetadata movieMetadata = insertMetadata("user-1", MediaMetadataOwnerType.MOVIE.getCode(), movie.getId());
        movieMetadata.setGenres("科幻");
        movieMetadata.setPosterFileNodeId(moviePoster.getId());
        mediaMetadataMapper.updateById(movieMetadata);
        movie.setMetadataId(movieMetadata.getId());
        mediaMovieMapper.updateById(movie);
        MediaGenreVo genre = mediaItemService.listGenres("user-1", movieDir.getId()).getFirst();
        assertEquals("/jcloud/api/media/metadata/" + movieMetadata.getId() + "/poster?v=4000", genre.getPosterUrl());

        MediaSeries series = insertSeries("user-1", "dir-1", "测试剧");
        MediaSeason season = insertSeason(series, 1);
        FileNode seasonPoster = insertFileNode("user-1", 3_000L);
        MediaMetadata seasonMetadata = insertMetadata("user-1", MediaMetadataOwnerType.SEASON.getCode(), season.getId());
        seasonMetadata.setPosterFileNodeId(seasonPoster.getId());
        mediaMetadataMapper.updateById(seasonMetadata);
        season.setMetadataId(seasonMetadata.getId());
        mediaSeasonMapper.updateById(season);

        MediaSeriesDetailVo detail = mediaItemService.getSeriesDetail(series.getId(), "user-1");
        assertEquals("/jcloud/api/media/metadata/" + seasonMetadata.getId() + "/poster?v=3000",
                detail.getSeasons().getFirst().getPosterUrl());
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

    /** 直插一个海报文件节点（Mapper 直插 PO），返回带指定 lastModified 的节点。 */
    private FileNode insertFileNode(String userId, long lastModified) {
        FileNode node = new FileNode();
        node.setId(IdUtil.nextId());
        node.setUserId(userId);
        node.setParentId(FileNodeConstants.ROOT_ID);
        node.setName("poster.jpg");
        node.setType(FileNodeConstants.TYPE_FILE);
        node.setSize(1024L);
        node.setPath(FileNodeConstants.ROOT_ID);
        node.setLastModified(lastModified);
        fileMapper.insert(node);
        return node;
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

    // ---------- 票据 02 辅助方法 ----------

    /** 直插带指定 createTime 的电影行（MyBatis-Plus 仅插入非空字段，不影响其余字段）。 */
    private MediaMovie insertMovieAt(String userId, String directoryId, String title, LocalDateTime createTime) {
        MediaMovie movie = insertMovie(userId, directoryId, title);
        MediaMovie update = new MediaMovie();
        update.setId(movie.getId());
        update.setCreateTime(createTime);
        mediaMovieMapper.updateById(update);
        return movie;
    }

    /** 直插带指定 minFileLastModified 的剧集行（剧墙「添加时间」按该字段排序）。 */
    private MediaSeries insertSeriesAt(String userId, String directoryId, String seriesName, Long minFileLastModified) {
        MediaSeries series = insertSeries(userId, directoryId, seriesName);
        MediaSeries update = new MediaSeries();
        update.setId(series.getId());
        update.setMinFileLastModified(minFileLastModified);
        mediaSeriesMapper.updateById(update);
        return series;
    }

    /** 直插带指定 createTime 的其他条目行。 */
    private MediaOther insertOtherAt(String userId, String directoryId, String name, LocalDateTime createTime) {
        MediaOther other = insertOther(userId, directoryId, name);
        MediaOther update = new MediaOther();
        update.setId(other.getId());
        update.setCreateTime(createTime);
        mediaOtherMapper.updateById(update);
        return other;
    }

    /** 给电影绑定指定发行日期的元数据。 */
    private void bindReleaseDate(MediaMovie movie, String releaseDate) {
        MediaMetadata metadata = insertMetadata(movie.getUserId(), MediaMetadataOwnerType.MOVIE.getCode(), movie.getId());
        metadata.setReleaseDate(releaseDate);
        mediaMetadataMapper.updateById(metadata);
        movie.setMetadataId(metadata.getId());
        mediaMovieMapper.updateById(movie);
    }

    /** 给剧集绑定指定发行日期的元数据。 */
    private void bindSeriesReleaseDate(MediaSeries series, String releaseDate) {
        MediaMetadata metadata = insertMetadata(series.getUserId(), MediaMetadataOwnerType.SERIES.getCode(), series.getId());
        metadata.setReleaseDate(releaseDate);
        mediaMetadataMapper.updateById(metadata);
        series.setMetadataId(metadata.getId());
        mediaSeriesMapper.updateById(series);
    }

    private IPage<MediaItemVo> moviesWithKeyword(String keyword) {
        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setKeyword(keyword);
        return mediaItemService.listMovies("user-1", query);
    }

    private IPage<MediaSeriesVo> seriesWithKeyword(String keyword) {
        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setKeyword(keyword);
        return mediaItemService.listSeries("user-1", query);
    }

    private IPage<MediaItemVo> othersWithKeyword(String keyword) {
        MediaPageQueryDto query = new MediaPageQueryDto();
        query.setKeyword(keyword);
        return mediaItemService.listOthers("user-1", query);
    }
}
