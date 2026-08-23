package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.enums.MediaFavoriteOwnerType;
import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaMovieVersionVo;
import com.fleyx.jcloud.service.MediaFavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 电影库新模型查询支撑组件（ADR 0021 / issue #18）：电影海报墙与详情查询走新表
 * （t_media_movie / t_media_movie_file / t_media_metadata），视图对象沿用现有 VO 并在
 * 详情中附加版本列表，保持响应结构兼容（前端适配在 issue #21）。
 * <p>
 * 海报墙装配走 {@link MediaItemQueryDriver} 共享管线（工单 08），本类仅保留类型差异层：
 * 版本聚合（版本列表/默认版本解析）、明细行代表文件与收藏 ownerType；分页 SQL 沿用 selectMoviePage。
 * 卡片文件事实取代表文件明细（last_play_file_id 指向的明细，未播放时取最早一条）；
 * 播放进度/最近播放时间取自电影行。
 */
@Component
@RequiredArgsConstructor
public class MediaMovieQuerySupport implements MediaItemQueryStrategy<MediaMovie, MediaMovieFile, MediaItemVo> {

    private final MediaMovieMapper mediaMovieMapper;
    private final MediaMovieFileMapper mediaMovieFileMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final FileMapper fileMapper;
    private final MediaItemVoSupport mediaItemVoSupport;
    private final MediaFavoriteService mediaFavoriteService;
    private final MediaItemQueryDriver queryDriver;

    /**
     * 电影海报墙：按电影聚合分页（新表，一部电影一张卡片），含匹配状态、进度与最近播放时间。
     */
    public IPage<MediaItemVo> listMovies(String userId, MediaPageQueryDto query) {
        return queryDriver.run(this, userId, query);
    }

    @Override
    public IPage<MediaMovie> page(String userId, MediaPageQueryDto query) {
        Page<MediaMovie> page = new Page<>(query.normalizedPageNum(), query.normalizedPageSize());
        return mediaMovieMapper.selectMoviePage(page, userId,
                MediaItemVoSupport.blankToNull(query.getKeyword()),
                MediaItemVoSupport.blankToNull(query.getDirectoryId()),
                MediaItemVoSupport.blankToNull(query.getGenre()),
                query.resolveSortField(), query.asc());
    }

    @Override
    public String metadataIdOf(MediaMovie row) {
        return row.getMetadataId();
    }

    @Override
    public Map<String, MediaMovieFile> representativeFiles(List<MediaMovie> rows) {
        List<String> movieIds = rows.stream().map(MediaMovie::getId).toList();
        if (movieIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<MediaMovieFile>> filesByMovie = mediaMovieFileMapper.selectList(
                        new LambdaQueryWrapper<MediaMovieFile>().in(MediaMovieFile::getMovieId, movieIds))
                .stream().collect(Collectors.groupingBy(MediaMovieFile::getMovieId));
        Map<String, MediaMovieFile> result = new HashMap<>();
        for (MediaMovie movie : rows) {
            MediaMovieFile chosen = MediaItemVoSupport.pickRepresentative(
                    filesByMovie.getOrDefault(movie.getId(), List.of()), movie.getLastPlayFileId(),
                    MediaMovieFile::getId, MediaMovieFile::getCreateTime);
            if (chosen != null) {
                result.put(movie.getId(), chosen);
            }
        }
        return result;
    }

    @Override
    public List<String> fileNodeIdsOf(Map<String, MediaMovieFile> representativeFiles) {
        return representativeFiles.values().stream().map(MediaMovieFile::getFileNodeId)
                .filter(Objects::nonNull).distinct().toList();
    }

    @Override
    public List<String> ownerIdsOf(List<MediaMovie> rows) {
        return rows.stream().map(MediaMovie::getId).toList();
    }

    @Override
    public MediaItemVo toCard(MediaMovie movie, MediaItemAssemblyContext<MediaMovieFile> ctx) {
        MediaMovieFile file = ctx.representativeOf(movie.getId());
        MediaMetadata metadata = ctx.metadataOf(movie.getMetadataId());
        MediaItemVo vo = new MediaItemVo();
        vo.setId(movie.getId());
        vo.setFileNodeId(file == null ? null : file.getFileNodeId());
        vo.setItemType(MediaItemType.MOVIE.getCode());
        vo.setFileName(file == null ? null : ctx.fileNameOf(file.getFileNodeId()));
        vo.setMatchStatus(movie.getMatchStatus());
        vo.setMetadataComplete(movie.getMetadataComplete());
        vo.setMetadataId(movie.getMetadataId());
        vo.setDurationMs(file == null ? null : file.getDurationMs());
        vo.setProgressMs(movie.getProgressMs());
        vo.setWatched(movie.getWatched());
        vo.setLastPlayTime(movie.getLastPlayTime());
        if (metadata != null) {
            vo.setTitle(metadata.getTitle());
            vo.setReleaseDate(metadata.getReleaseDate());
            vo.setVoteAverage(metadata.getVoteAverage());
            vo.setPosterUrl(mediaItemVoSupport.posterUrlOf(metadata, ctx.nodeVersionMap()));
        }
        if (vo.getTitle() == null) {
            vo.setTitle(movie.getTitle());
        }
        vo.setFavorited(ctx.isFavorited(movie.getId()));
        return vo;
    }

    @Override
    public MediaFavoriteOwnerType favoriteOwner() {
        return MediaFavoriteOwnerType.MOVIE;
    }

    /**
     * 电影详情：电影行 + 元数据 + 版本列表（全部明细行，多版本共享进度）。
     */
    public MediaItemDetailVo getMovieDetail(String movieId, String userId) {
        MediaMovie movie = mediaMovieMapper.selectById(movieId);
        if (movie == null || !userId.equals(movie.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "电影不存在");
        }
        List<MediaMovieFile> files = mediaMovieFileMapper.selectList(
                new LambdaQueryWrapper<MediaMovieFile>().eq(MediaMovieFile::getMovieId, movieId));
        MediaMovieFile representative = MediaItemVoSupport.pickRepresentative(
                files, movie.getLastPlayFileId(), MediaMovieFile::getId, MediaMovieFile::getCreateTime);
        FileNode node = representative == null ? null : fileMapper.selectById(representative.getFileNodeId());
        MediaMetadata metadata = movie.getMetadataId() == null ? null
                : mediaMetadataMapper.selectById(movie.getMetadataId());
        Map<String, Long> nodeVersionMap = metadata == null ? Map.of() : mediaItemVoSupport.loadNodeVersionMap(
                Arrays.asList(metadata.getPosterFileNodeId(), metadata.getBackdropFileNodeId()));

        MediaItemDetailVo vo = new MediaItemDetailVo();
        vo.setId(movie.getId());
        vo.setItemType(MediaItemType.MOVIE.getCode());
        vo.setFileName(node == null ? null : node.getName());
        vo.setFileSize(node == null ? null : node.getSize());
        vo.setMatchStatus(movie.getMatchStatus());
        vo.setMetadataComplete(movie.getMetadataComplete());
        vo.setMetadataId(movie.getMetadataId());
        vo.setDurationMs(representative == null ? null : representative.getDurationMs());
        vo.setProgressMs(movie.getProgressMs());
        vo.setWatched(movie.getWatched());
        if (representative != null) {
            vo.setWidth(representative.getWidth());
            vo.setHeight(representative.getHeight());
            vo.setVideoCodec(representative.getVideoCodec());
            vo.setAudioCodec(representative.getAudioCodec());
        }
        if (metadata != null) {
            vo.setTitle(metadata.getTitle());
            vo.setOriginalTitle(metadata.getOriginalTitle());
            vo.setOverview(metadata.getOverview());
            vo.setReleaseDate(metadata.getReleaseDate());
            vo.setVoteAverage(metadata.getVoteAverage());
            vo.setPosterUrl(mediaItemVoSupport.posterUrlOf(metadata, nodeVersionMap));
            vo.setBackdropUrl(mediaItemVoSupport.backdropUrlOf(metadata, nodeVersionMap));
        }
        if (vo.getTitle() == null) {
            vo.setTitle(movie.getTitle());
        }
        // 新元数据表无类型标签字段（issue #20 削刮切换时对齐），保持空列表兼容
        vo.setGenres(List.of());
        vo.setVersions(toVersionVos(files));
        vo.setDefaultVersionId(resolveDefaultVersionId(movie, files));
        vo.setFavorited(mediaFavoriteService.listFavoritedOwnerIds(userId, MediaFavoriteOwnerType.MOVIE,
                List.of(movieId)).contains(movieId));
        return vo;
    }

    /**
     * 明细行列表转版本视图（按 create_time 升序、id 兜底，保证列表顺序确定）：文件名补齐，其余字段为文件事实。
     */
    private List<MediaMovieVersionVo> toVersionVos(List<MediaMovieFile> files) {
        if (files.isEmpty()) {
            return List.of();
        }
        Map<String, String> nameMap = mediaItemVoSupport.loadFileNameMap(
                files.stream().map(MediaMovieFile::getFileNodeId).toList());
        List<MediaMovieVersionVo> result = new ArrayList<>();
        for (MediaMovieFile file : sortedByCreateTime(files)) {
            MediaMovieVersionVo version = new MediaMovieVersionVo();
            version.setId(file.getId());
            version.setFileNodeId(file.getFileNodeId());
            version.setFileName(nameMap.get(file.getFileNodeId()));
            version.setFileSize(file.getFileSize());
            version.setDurationMs(file.getDurationMs());
            version.setContainer(file.getContainer());
            version.setVideoCodec(file.getVideoCodec());
            version.setAudioCodec(file.getAudioCodec());
            version.setWidth(file.getWidth());
            version.setHeight(file.getHeight());
            result.add(version);
        }
        return result;
    }

    /**
     * 默认播放版本 ID：last_play_file_id 指向的明细行仍存在时取它，否则取最早（create_time 升序）明细行 ID。
     */
    private String resolveDefaultVersionId(MediaMovie movie, List<MediaMovieFile> files) {
        if (files.isEmpty()) {
            return null;
        }
        if (movie.getLastPlayFileId() != null
                && files.stream().anyMatch(f -> f.getId().equals(movie.getLastPlayFileId()))) {
            return movie.getLastPlayFileId();
        }
        return sortedByCreateTime(files).getFirst().getId();
    }

    /**
     * 明细行按 create_time 升序（null 排后）与 id 兜底排序，保证版本列表顺序确定（issue #21 收尾）。
     */
    private List<MediaMovieFile> sortedByCreateTime(List<MediaMovieFile> files) {
        return files.stream()
                .sorted(Comparator.comparing(MediaMovieFile::getCreateTime,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(MediaMovieFile::getId))
                .toList();
    }
}
