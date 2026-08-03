package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.enums.MediaFavoriteOwnerType;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.common.enums.MediaMetadataSource;
import com.fleyx.jcloud.common.enums.MediaPersistStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaFavoriteMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.MediaFavoriteQueryDto;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaFavorite;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.MediaFavoriteVo;
import com.fleyx.jcloud.util.IdUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 我的收藏分页查询测试（工单 02：GET /media/favorites 后端语义）。
 * <p>
 * 覆盖：五类实体各收藏后命中正确分区与卡片字段（含元数据海报、集海报回退剧集）、
 * 收藏时间倒序、库过滤（含 season/episode 经 series 归属、跨库不串）、分页、用户隔离、空结果。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaFavoriteQueryTest {

    @Autowired
    private MediaFavoriteService mediaFavoriteService;

    @Autowired
    private MediaFavoriteMapper mediaFavoriteMapper;

    @Autowired
    private MediaMovieMapper mediaMovieMapper;

    @Autowired
    private MediaSeriesMapper mediaSeriesMapper;

    @Autowired
    private MediaSeasonMapper mediaSeasonMapper;

    @Autowired
    private MediaEpisodeMapper mediaEpisodeMapper;

    @Autowired
    private MediaOtherMapper mediaOtherMapper;

    @Autowired
    private MediaMetadataMapper mediaMetadataMapper;

    /**
     * 五类实体各收藏后，按分区查询命中正确卡片字段；
     * 集自身无元数据海报时回退剧集海报；other 输出 fileName/fileNodeId/durationMs。
     */
    @Test
    void pageFavoritesReturnsFiveSectionsWithCardFields() {
        // 电影：带元数据与海报
        MediaMovie movie = insertMovie("user-1", "dir-1", "测试电影", MediaMatchStatus.MATCHED.getCode());
        MediaMetadata movieMetadata = insertMetadata("user-1", MediaMetadataOwnerType.MOVIE.getCode(),
                movie.getId(), "poster-node-1");
        movie.setMetadataId(movieMetadata.getId());
        mediaMovieMapper.updateById(movie);

        // 剧集：带元数据与海报；季：无元数据；集：无自身元数据（回退剧集海报）
        MediaSeries series = insertSeries("user-1", "dir-1", "测试剧");
        MediaMetadata seriesMetadata = insertMetadata("user-1", MediaMetadataOwnerType.SERIES.getCode(),
                series.getId(), "poster-node-2");
        series.setMetadataId(seriesMetadata.getId());
        mediaSeriesMapper.updateById(series);
        MediaSeason season = insertSeason(series, 1);
        MediaEpisode episode = insertEpisode(series, season, 1);

        MediaOther other = insertOther("user-1", "dir-1");

        mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.MOVIE, movie.getId());
        mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.SERIES, series.getId());
        mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.SEASON, season.getId());
        mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.EPISODE, episode.getId());
        mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.OTHER, other.getId());

        IPage<MediaFavoriteVo> movies = query("user-1", MediaFavoriteOwnerType.MOVIE, null, 1, 24);
        assertEquals(1, movies.getTotal());
        MediaFavoriteVo movieVo = movies.getRecords().getFirst();
        assertEquals("movie", movieVo.getOwnerType());
        assertEquals(movie.getId(), movieVo.getOwnerId());
        assertEquals("元数据-movie", movieVo.getTitle());
        assertEquals("/jcloud/api/media/metadata/" + movieMetadata.getId() + "/poster", movieVo.getPosterUrl());
        assertEquals("2024-05-01", movieVo.getReleaseDate());
        assertEquals(8.7, movieVo.getVoteAverage());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), movieVo.getMatchStatus());
        assertTrue(movieVo.getFavorited());

        IPage<MediaFavoriteVo> seriesPage = query("user-1", MediaFavoriteOwnerType.SERIES, null, 1, 24);
        MediaFavoriteVo seriesVo = seriesPage.getRecords().getFirst();
        assertEquals(series.getId(), seriesVo.getOwnerId());
        assertEquals("元数据-series", seriesVo.getTitle());
        assertEquals("/jcloud/api/media/metadata/" + seriesMetadata.getId() + "/poster", seriesVo.getPosterUrl());

        IPage<MediaFavoriteVo> seasons = query("user-1", MediaFavoriteOwnerType.SEASON, null, 1, 24);
        MediaFavoriteVo seasonVo = seasons.getRecords().getFirst();
        assertEquals(season.getId(), seasonVo.getOwnerId());
        assertEquals(series.getId(), seasonVo.getSeriesId());
        assertEquals("测试剧", seasonVo.getSeriesName());
        assertEquals(1, seasonVo.getSeasonNo());
        assertNull(seasonVo.getPosterUrl());

        IPage<MediaFavoriteVo> episodes = query("user-1", MediaFavoriteOwnerType.EPISODE, null, 1, 24);
        MediaFavoriteVo episodeVo = episodes.getRecords().getFirst();
        assertEquals(episode.getId(), episodeVo.getOwnerId());
        assertEquals(series.getId(), episodeVo.getSeriesId());
        assertEquals("测试剧", episodeVo.getSeriesName());
        assertEquals(1, episodeVo.getSeasonNo());
        assertEquals(1, episodeVo.getEpisodeNo());
        // 集自身无元数据海报 → 回退剧集海报
        assertEquals("/jcloud/api/media/metadata/" + seriesMetadata.getId() + "/poster", episodeVo.getPosterUrl());

        IPage<MediaFavoriteVo> others = query("user-1", MediaFavoriteOwnerType.OTHER, null, 1, 24);
        MediaFavoriteVo otherVo = others.getRecords().getFirst();
        assertEquals(other.getId(), otherVo.getOwnerId());
        assertEquals("测试视频.mp4", otherVo.getTitle());
        assertEquals("测试视频.mp4", otherVo.getFileName());
        assertEquals(other.getFileNodeId(), otherVo.getFileNodeId());
        assertNull(otherVo.getPosterUrl());
    }

    /**
     * 分区内按收藏时间倒序（create_time desc, id desc 兜底）。
     */
    @Test
    void pageFavoritesOrderedByCreateTimeDesc() {
        MediaMovie first = insertMovie("user-1", "dir-1", "最早", MediaMatchStatus.UNMATCHED.getCode());
        MediaMovie second = insertMovie("user-1", "dir-1", "中间", MediaMatchStatus.UNMATCHED.getCode());
        MediaMovie third = insertMovie("user-1", "dir-1", "最新", MediaMatchStatus.UNMATCHED.getCode());
        LocalDateTime now = LocalDateTime.now();
        insertFavorite("user-1", MediaFavoriteOwnerType.MOVIE, first.getId(), now.minusHours(2));
        insertFavorite("user-1", MediaFavoriteOwnerType.MOVIE, second.getId(), now.minusHours(1));
        insertFavorite("user-1", MediaFavoriteOwnerType.MOVIE, third.getId(), now);

        IPage<MediaFavoriteVo> page = query("user-1", MediaFavoriteOwnerType.MOVIE, null, 1, 24);
        List<String> ids = page.getRecords().stream().map(MediaFavoriteVo::getOwnerId).toList();
        assertEquals(List.of(third.getId(), second.getId(), first.getId()), ids);
        assertTrue(page.getRecords().getFirst().getFavoriteTime().isAfter(
                page.getRecords().getLast().getFavoriteTime()));
    }

    /**
     * 库过滤：movie/series/other 按 directory_id；season/episode 经 series 归属；
     * 跨库收藏不串（同一用户不同库）。
     */
    @Test
    void pageFavoritesFiltersByDirectory() {
        MediaMovie dir1Movie = insertMovie("user-1", "dir-1", "库一电影", MediaMatchStatus.UNMATCHED.getCode());
        MediaMovie dir2Movie = insertMovie("user-1", "dir-2", "库二电影", MediaMatchStatus.UNMATCHED.getCode());
        MediaSeries dir1Series = insertSeries("user-1", "dir-1", "库一剧");
        MediaSeries dir2Series = insertSeries("user-1", "dir-2", "库二剧");
        MediaSeason dir1Season = insertSeason(dir1Series, 1);
        MediaSeason dir2Season = insertSeason(dir2Series, 1);
        MediaEpisode dir1Episode = insertEpisode(dir1Series, dir1Season, 1);
        MediaEpisode dir2Episode = insertEpisode(dir2Series, dir2Season, 1);
        MediaOther dir1Other = insertOther("user-1", "dir-1");
        MediaOther dir2Other = insertOther("user-1", "dir-2");

        for (MediaFavoriteOwnerType type : MediaFavoriteOwnerType.values()) {
            mediaFavoriteService.toggle("user-1", type, switch (type) {
                case MOVIE -> dir1Movie.getId();
                case SERIES -> dir1Series.getId();
                case SEASON -> dir1Season.getId();
                case EPISODE -> dir1Episode.getId();
                case OTHER -> dir1Other.getId();
            });
            mediaFavoriteService.toggle("user-1", type, switch (type) {
                case MOVIE -> dir2Movie.getId();
                case SERIES -> dir2Series.getId();
                case SEASON -> dir2Season.getId();
                case EPISODE -> dir2Episode.getId();
                case OTHER -> dir2Other.getId();
            });
        }

        assertEquals(List.of(dir1Movie.getId()), ids(query("user-1", MediaFavoriteOwnerType.MOVIE, "dir-1", 1, 24)));
        assertEquals(List.of(dir2Movie.getId()), ids(query("user-1", MediaFavoriteOwnerType.MOVIE, "dir-2", 1, 24)));
        assertEquals(List.of(dir1Series.getId()), ids(query("user-1", MediaFavoriteOwnerType.SERIES, "dir-1", 1, 24)));
        assertEquals(List.of(dir1Season.getId()), ids(query("user-1", MediaFavoriteOwnerType.SEASON, "dir-1", 1, 24)));
        assertEquals(List.of(dir2Season.getId()), ids(query("user-1", MediaFavoriteOwnerType.SEASON, "dir-2", 1, 24)));
        assertEquals(List.of(dir1Episode.getId()), ids(query("user-1", MediaFavoriteOwnerType.EPISODE, "dir-1", 1, 24)));
        assertEquals(List.of(dir2Episode.getId()), ids(query("user-1", MediaFavoriteOwnerType.EPISODE, "dir-2", 1, 24)));
        assertEquals(List.of(dir1Other.getId()), ids(query("user-1", MediaFavoriteOwnerType.OTHER, "dir-1", 1, 24)));
        assertEquals(List.of(dir2Other.getId()), ids(query("user-1", MediaFavoriteOwnerType.OTHER, "dir-2", 1, 24)));
    }

    /**
     * 分页：pageSize=1 翻页 total 正确、无重复。
     */
    @Test
    void pageFavoritesPaginates() {
        MediaMovie first = insertMovie("user-1", "dir-1", "最早", MediaMatchStatus.UNMATCHED.getCode());
        MediaMovie second = insertMovie("user-1", "dir-1", "中间", MediaMatchStatus.UNMATCHED.getCode());
        MediaMovie third = insertMovie("user-1", "dir-1", "最新", MediaMatchStatus.UNMATCHED.getCode());
        LocalDateTime now = LocalDateTime.now();
        insertFavorite("user-1", MediaFavoriteOwnerType.MOVIE, first.getId(), now.minusHours(2));
        insertFavorite("user-1", MediaFavoriteOwnerType.MOVIE, second.getId(), now.minusHours(1));
        insertFavorite("user-1", MediaFavoriteOwnerType.MOVIE, third.getId(), now);

        IPage<MediaFavoriteVo> page1 = query("user-1", MediaFavoriteOwnerType.MOVIE, null, 1, 1);
        assertEquals(3, page1.getTotal());
        assertEquals(List.of(third.getId()), ids(page1));

        IPage<MediaFavoriteVo> page2 = query("user-1", MediaFavoriteOwnerType.MOVIE, null, 2, 1);
        assertEquals(3, page2.getTotal());
        assertEquals(List.of(second.getId()), ids(page2));

        IPage<MediaFavoriteVo> page3 = query("user-1", MediaFavoriteOwnerType.MOVIE, null, 3, 1);
        assertEquals(3, page3.getTotal());
        assertEquals(List.of(first.getId()), ids(page3));
    }

    /**
     * 用户隔离：他人收藏不可见；无收藏时返回空页。
     */
    @Test
    void pageFavoritesIsolatedPerUserAndEmpty() {
        MediaMovie movie = insertMovie("user-1", "dir-1", "测试电影", MediaMatchStatus.UNMATCHED.getCode());
        mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.MOVIE, movie.getId());

        IPage<MediaFavoriteVo> foreign = query("user-2", MediaFavoriteOwnerType.MOVIE, null, 1, 24);
        assertEquals(0, foreign.getTotal());
        assertTrue(foreign.getRecords().isEmpty());

        IPage<MediaFavoriteVo> empty = query("user-1", MediaFavoriteOwnerType.SERIES, null, 1, 24);
        assertEquals(0, empty.getTotal());
        assertTrue(empty.getRecords().isEmpty());
    }

    /**
     * 实体已被删但收藏残留的行跳过（正常级联已清理，此处兜底）。
     */
    @Test
    void pageFavoritesSkipsMissingEntityRows() {
        MediaMovie movie = insertMovie("user-1", "dir-1", "测试电影", MediaMatchStatus.UNMATCHED.getCode());
        mediaFavoriteService.toggle("user-1", MediaFavoriteOwnerType.MOVIE, movie.getId());
        mediaMovieMapper.deleteById(movie.getId());

        IPage<MediaFavoriteVo> page = query("user-1", MediaFavoriteOwnerType.MOVIE, null, 1, 24);
        assertTrue(page.getRecords().isEmpty());
    }

    /**
     * 库过滤集合为空（该库无对应类型实体）时直接返回空页。
     */
    @Test
    void pageFavoritesReturnsEmptyWhenDirectoryHasNoEntities() {
        IPage<MediaFavoriteVo> page = query("user-1", MediaFavoriteOwnerType.MOVIE, "not-exists", 1, 24);
        assertEquals(0, page.getTotal());
        assertTrue(page.getRecords().isEmpty());
    }

    /**
     * 未知收藏类型编码按参数错误拦截。
     */
    @Test
    void pageFavoritesRejectsUnknownOwnerType() {
        MediaFavoriteQueryDto dto = new MediaFavoriteQueryDto();
        dto.setOwnerType("unknown");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> mediaFavoriteService.pageFavorites("user-1", dto));
        assertEquals(ResultCode.PARAM_ERROR, ex.getResultCode());
    }

    private IPage<MediaFavoriteVo> query(String userId, MediaFavoriteOwnerType ownerType, String directoryId,
                                         int pageNum, int pageSize) {
        MediaFavoriteQueryDto dto = new MediaFavoriteQueryDto();
        dto.setOwnerType(ownerType.getCode());
        dto.setDirectoryId(directoryId);
        dto.setPageNum(pageNum);
        dto.setPageSize(pageSize);
        return mediaFavoriteService.pageFavorites(userId, dto);
    }

    private List<String> ids(IPage<MediaFavoriteVo> page) {
        return page.getRecords().stream().map(MediaFavoriteVo::getOwnerId).toList();
    }

    private void insertFavorite(String userId, MediaFavoriteOwnerType ownerType, String ownerId, LocalDateTime time) {
        MediaFavorite favorite = new MediaFavorite();
        favorite.setId(IdUtil.nextId());
        favorite.setUserId(userId);
        favorite.setOwnerType(ownerType.getCode());
        favorite.setOwnerId(ownerId);
        favorite.setCreateTime(time);
        mediaFavoriteMapper.insert(favorite);
    }

    private MediaMovie insertMovie(String userId, String directoryId, String title, String matchStatus) {
        MediaMovie movie = new MediaMovie();
        movie.setUserId(userId);
        movie.setDirectoryId(directoryId);
        movie.setFolderNodeId(IdUtil.nextId());
        movie.setTitle(title);
        movie.setMatchStatus(matchStatus);
        movie.setMetadataComplete(false);
        mediaMovieMapper.insert(movie);
        return movie;
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

    private MediaEpisode insertEpisode(MediaSeries series, MediaSeason season, int episodeNo) {
        MediaEpisode episode = new MediaEpisode();
        episode.setSeriesId(series.getId());
        episode.setSeasonId(season.getId());
        episode.setEpisodeNo(episodeNo);
        mediaEpisodeMapper.insert(episode);
        return episode;
    }

    private MediaOther insertOther(String userId, String directoryId) {
        MediaOther other = new MediaOther();
        other.setUserId(userId);
        other.setDirectoryId(directoryId);
        other.setFileNodeId(IdUtil.nextId());
        other.setName("测试视频.mp4");
        mediaOtherMapper.insert(other);
        return other;
    }

    private MediaMetadata insertMetadata(String userId, String ownerType, String ownerId, String posterNodeId) {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setUserId(userId);
        metadata.setOwnerType(ownerType);
        metadata.setOwnerId(ownerId);
        metadata.setSource(MediaMetadataSource.TMDB.getCode());
        metadata.setPersistStatus(MediaPersistStatus.PERSISTED.getCode());
        metadata.setTitle("元数据-" + ownerType);
        metadata.setReleaseDate("2024-05-01");
        metadata.setVoteAverage(8.7);
        metadata.setPosterFileNodeId(posterNodeId);
        mediaMetadataMapper.insert(metadata);
        return metadata;
    }
}
