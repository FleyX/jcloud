package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSeriesV2Mapper;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.po.MediaSeriesV2;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 播放链路标题级解析支撑组件（issue #19）。
 * <p>
 * 播放入参 ID 为标题级行 ID：电影 → t_media_movie、集 → t_media_episode、其他 → t_media_other。
 * 解析结果为可播放文件事实（来自文件明细行或 other 行，进度取自标题级行）；
 * 版本文件定位：last_play_file_id 优先，缺省取最早一条明细（续播按 last_play_file_id 定位版本）。
 */
@Component
@RequiredArgsConstructor
public class MediaPlaybackResolveSupport {

    private final MediaMovieMapper mediaMovieMapper;
    private final MediaMovieFileMapper mediaMovieFileMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaEpisodeFileMapper mediaEpisodeFileMapper;
    private final MediaOtherMapper mediaOtherMapper;
    private final MediaSeriesV2Mapper mediaSeriesV2Mapper;

    /**
     * 解析后的可播放文件事实（文件明细行或 other 行，进度取自标题级行）。
     */
    public record Playable(String fileRowId, String fileNodeId, Long fileSize, Long durationMs,
                           String container, String videoCodec, String audioCodec,
                           Integer width, Integer height, Long progressMs) {
    }

    /**
     * 按标题级行 ID 解析可播放文件事实：电影 → 电影行 + 版本明细（last_play_file_id 优先，缺省取最早）；
     * 集 → 集行 + 集文件明细；其他 → other 行（文件级本身）。进度一律取标题级行。
     */
    public Playable resolve(String id, String userId) {
        MediaMovie movie = mediaMovieMapper.selectById(id);
        if (movie != null) {
            if (!userId.equals(movie.getUserId())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
            }
            MediaMovieFile file = pickMovieFile(movie);
            if (file == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
            }
            return new Playable(file.getId(), file.getFileNodeId(), file.getFileSize(), file.getDurationMs(),
                    file.getContainer(), file.getVideoCodec(), file.getAudioCodec(),
                    file.getWidth(), file.getHeight(), movie.getProgressMs());
        }
        MediaEpisode episode = mediaEpisodeMapper.selectById(id);
        if (episode != null) {
            MediaSeriesV2 series = mediaSeriesV2Mapper.selectById(episode.getSeriesId());
            if (series == null || !userId.equals(series.getUserId())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
            }
            MediaEpisodeFile file = pickEpisodeFile(episode);
            if (file == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
            }
            return new Playable(file.getId(), file.getFileNodeId(), file.getFileSize(), file.getDurationMs(),
                    file.getContainer(), file.getVideoCodec(), file.getAudioCodec(),
                    file.getWidth(), file.getHeight(), episode.getProgressMs());
        }
        MediaOther other = mediaOtherMapper.selectById(id);
        if (other == null || !userId.equals(other.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
        }
        return new Playable(other.getId(), other.getFileNodeId(), null, other.getDurationMs(),
                other.getContainer(), other.getVideoCodec(), other.getAudioCodec(),
                other.getWidth(), other.getHeight(), other.getProgressMs());
    }

    /**
     * 定位电影版本明细：last_play_file_id 指向的明细仍在版本列表中时返回它，否则取最早一条。
     */
    public MediaMovieFile pickMovieFile(MediaMovie movie) {
        List<MediaMovieFile> files = mediaMovieFileMapper.selectList(
                new LambdaQueryWrapper<MediaMovieFile>().eq(MediaMovieFile::getMovieId, movie.getId()));
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

    /**
     * 定位集文件明细：last_play_file_id 指向的明细仍在列表中时返回它，否则取最早一条。
     */
    public MediaEpisodeFile pickEpisodeFile(MediaEpisode episode) {
        List<MediaEpisodeFile> files = mediaEpisodeFileMapper.selectList(
                new LambdaQueryWrapper<MediaEpisodeFile>().eq(MediaEpisodeFile::getEpisodeId, episode.getId()));
        if (files.isEmpty()) {
            return null;
        }
        return files.stream().filter(f -> f.getId().equals(episode.getLastPlayFileId())).findFirst()
                .orElseGet(() -> files.stream()
                        .min(Comparator.comparing(MediaEpisodeFile::getCreateTime,
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(MediaEpisodeFile::getId))
                        .orElse(files.getFirst()));
    }
}
