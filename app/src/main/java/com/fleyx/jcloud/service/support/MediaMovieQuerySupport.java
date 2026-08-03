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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 电影库新模型查询支撑组件（ADR 0021 / issue #18）：电影海报墙与详情查询走新表
 * （t_media_movie / t_media_movie_file / t_media_metadata），视图对象沿用现有 VO 并在
 * 详情中附加版本列表，保持响应结构兼容（前端适配在 issue #21）。
 * <p>
 * 海报墙一部电影一行（多版本聚合为一张卡片）：卡片文件事实取代表文件明细
 * （last_play_file_id 指向的明细，未播放时取最早一条）；播放进度/最近播放时间取自电影行。
 */
@Component
@RequiredArgsConstructor
public class MediaMovieQuerySupport {

    private final MediaMovieMapper mediaMovieMapper;
    private final MediaMovieFileMapper mediaMovieFileMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final FileMapper fileMapper;
    private final MediaItemVoSupport mediaItemVoSupport;
    private final MediaFavoriteService mediaFavoriteService;

    /**
     * 电影海报墙：按电影聚合分页（新表，一部电影一张卡片），含匹配状态、进度与最近播放时间。
     */
    public IPage<MediaItemVo> listMovies(String userId, MediaPageQueryDto query) {
        Page<MediaMovie> page = new Page<>(query.normalizedPageNum(), query.normalizedPageSize());
        IPage<MediaMovie> result = mediaMovieMapper.selectMoviePage(page, userId,
                blankToNull(query.getKeyword()), blankToNull(query.getDirectoryId()),
                resolveSortField(query), query.asc());
        List<MediaMovie> movies = result.getRecords();
        Map<String, MediaMetadata> metadataMap = loadMetadataMap(
                movies.stream().map(MediaMovie::getMetadataId).toList());
        Map<String, MediaMovieFile> representativeFile = representativeFiles(movies);
        Map<String, String> fileNameMap = loadFileNameMap(
                representativeFile.values().stream().map(MediaMovieFile::getFileNodeId).toList());

        List<MediaItemVo> vos = new ArrayList<>();
        for (MediaMovie movie : movies) {
            MediaMovieFile file = representativeFile.get(movie.getId());
            MediaMetadata metadata = movie.getMetadataId() == null ? null : metadataMap.get(movie.getMetadataId());
            MediaItemVo vo = new MediaItemVo();
            vo.setId(movie.getId());
            vo.setFileNodeId(file == null ? null : file.getFileNodeId());
            vo.setItemType(MediaItemType.MOVIE.getCode());
            vo.setFileName(file == null ? null : fileNameMap.get(file.getFileNodeId()));
            vo.setMatchStatus(movie.getMatchStatus());
            vo.setMetadataComplete(movie.getMetadataComplete());
            vo.setMetadataId(movie.getMetadataId());
            vo.setDurationMs(file == null ? null : file.getDurationMs());
            vo.setProgressMs(movie.getProgressMs());
            vo.setLastPlayTime(movie.getLastPlayTime());
            if (metadata != null) {
                vo.setTitle(metadata.getTitle());
                vo.setReleaseDate(metadata.getReleaseDate());
                vo.setVoteAverage(metadata.getVoteAverage());
                vo.setPosterUrl(posterUrlOf(metadata));
            }
            if (vo.getTitle() == null) {
                vo.setTitle(movie.getTitle());
            }
            vos.add(vo);
        }
        // 当前用户收藏状态批量填充（ownerType=MOVIE）
        Set<String> favoritedIds = mediaFavoriteService.listFavoritedOwnerIds(userId, MediaFavoriteOwnerType.MOVIE,
                movies.stream().map(MediaMovie::getId).toList());
        for (MediaItemVo vo : vos) {
            vo.setFavorited(favoritedIds.contains(vo.getId()));
        }
        Page<MediaItemVo> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(vos);
        return voPage;
    }

    /**
     * 排序字段解析：release / rating / title 透传，其余（含非法值）回退 added。
     */
    private String resolveSortField(MediaPageQueryDto query) {
        if (query.sortByRelease()) {
            return MediaPageQueryDto.SORT_FIELD_RELEASE;
        }
        if (query.sortByRating()) {
            return MediaPageQueryDto.SORT_FIELD_RATING;
        }
        if (query.sortByTitle()) {
            return MediaPageQueryDto.SORT_FIELD_TITLE;
        }
        return MediaPageQueryDto.SORT_FIELD_ADDED;
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
        MediaMovieFile representative = pickRepresentative(movie, files);
        FileNode node = representative == null ? null : fileMapper.selectById(representative.getFileNodeId());
        MediaMetadata metadata = movie.getMetadataId() == null ? null
                : mediaMetadataMapper.selectById(movie.getMetadataId());

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
            vo.setPosterUrl(posterUrlOf(metadata));
            vo.setBackdropUrl(backdropUrlOf(metadata));
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
        Map<String, String> nameMap = loadFileNameMap(
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

    /**
     * 各电影的代表文件明细：last_play_file_id 优先，否则取最早一条明细。
     */
    private Map<String, MediaMovieFile> representativeFiles(List<MediaMovie> movies) {
        List<String> movieIds = movies.stream().map(MediaMovie::getId).toList();
        if (movieIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<MediaMovieFile>> filesByMovie = mediaMovieFileMapper.selectList(
                        new LambdaQueryWrapper<MediaMovieFile>().in(MediaMovieFile::getMovieId, movieIds))
                .stream().collect(Collectors.groupingBy(MediaMovieFile::getMovieId));
        Map<String, MediaMovieFile> result = new HashMap<>();
        for (MediaMovie movie : movies) {
            MediaMovieFile chosen = pickRepresentative(movie, filesByMovie.getOrDefault(movie.getId(), List.of()));
            if (chosen != null) {
                result.put(movie.getId(), chosen);
            }
        }
        return result;
    }

    private MediaMovieFile pickRepresentative(MediaMovie movie, List<MediaMovieFile> files) {
        if (files.isEmpty()) {
            return null;
        }
        return files.stream().filter(f -> f.getId().equals(movie.getLastPlayFileId())).findFirst()
                .orElseGet(() -> files.stream()
                        .min(Comparator.comparing(MediaMovieFile::getCreateTime,
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(MediaMovieFile::getId))
                        .orElse(files.getFirst()));
    }

    private Map<String, MediaMetadata> loadMetadataMap(List<String> metadataIds) {
        List<String> ids = metadataIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return mediaMetadataMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(MediaMetadata::getId, Function.identity()));
    }

    private Map<String, String> loadFileNameMap(List<String> fileNodeIds) {
        List<String> ids = fileNodeIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return fileMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(FileNode::getId, FileNode::getName));
    }

    /**
     * 元数据海报图 URL，无海报时返回 null。
     */
    private String posterUrlOf(MediaMetadata metadata) {
        return metadata == null || metadata.getPosterFileNodeId() == null ? null
                : mediaItemVoSupport.metadataPosterUrl(metadata.getId());
    }

    /**
     * 元数据背景图 URL，无背景图时返回 null。
     */
    private String backdropUrlOf(MediaMetadata metadata) {
        return metadata == null || metadata.getBackdropFileNodeId() == null ? null
                : mediaItemVoSupport.metadataBackdropUrl(metadata.getId());
    }

    private String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
