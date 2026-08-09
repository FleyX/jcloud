package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMovieFileMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.po.MediaSeries;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
    private final MediaSeriesMapper mediaSeriesMapper;

    /**
     * 解析后的可播放文件事实（文件明细行或 other 行，进度取自标题级行）。
     */
    public record Playable(String fileRowId, String fileNodeId, Long fileSize, Long durationMs,
                           String container, String videoCodec, String audioCodec,
                           Integer width, Integer height, Long progressMs) {
    }

    /**
     * 按标题级行 ID 解析可播放文件事实（缺省版本：电影 → last_play_file_id 优先、缺省取最早明细）。
     */
    public Playable resolve(String id, String userId) {
        return resolve(id, userId, null);
    }

    /**
     * 按标题级行 ID 解析可播放文件事实（可指定版本）：电影 → 电影行 + 版本明细
     * （versionId 非空时校验其属于此电影并使用该版本，缺省 last_play_file_id 优先、缺省取最早）；
     * 集 → 集行 + 集文件明细；其他 → other 行（文件级本身）。进度一律取标题级行。
     * versionId 仅对电影有意义，集/其他传入时忽略（不报错，行为与缺省一致）。
     */
    public Playable resolve(String id, String userId, String versionId) {
        MediaMovie movie = mediaMovieMapper.selectById(id);
        if (movie != null) {
            if (!userId.equals(movie.getUserId())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
            }
            MediaMovieFile file = versionId == null ? pickMovieFile(movie) : pickVersion(movie, versionId);
            if (file == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
            }
            return new Playable(file.getId(), file.getFileNodeId(), file.getFileSize(), file.getDurationMs(),
                    file.getContainer(), file.getVideoCodec(), file.getAudioCodec(),
                    file.getWidth(), file.getHeight(), movie.getProgressMs());
        }
        MediaEpisode episode = mediaEpisodeMapper.selectById(id);
        if (episode != null) {
            MediaSeries series = mediaSeriesMapper.selectById(episode.getSeriesId());
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
     * 定位指定版本的电影文件明细：明细行存在且属于该电影时返回，否则抛业务异常。
     * 供播放链路（getPlaybackInfo/stream/transcode/字幕）与进度上报共用。
     */
    public MediaMovieFile pickVersion(MediaMovie movie, String versionId) {
        MediaMovieFile file = mediaMovieFileMapper.selectById(versionId);
        if (file == null || !movie.getId().equals(file.getMovieId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "版本不存在");
        }
        return file;
    }

    /**
     * 定位电影版本明细：last_play_file_id 指向的明细仍在版本列表中时返回它，否则取最早一条。
     */
    public MediaMovieFile pickMovieFile(MediaMovie movie) {
        List<MediaMovieFile> files = mediaMovieFileMapper.selectList(
                new LambdaQueryWrapper<MediaMovieFile>().eq(MediaMovieFile::getMovieId, movie.getId()));
        return MediaItemVoSupport.pickRepresentative(files, movie.getLastPlayFileId(),
                MediaMovieFile::getId, MediaMovieFile::getCreateTime);
    }

    /**
     * 定位集文件明细：last_play_file_id 指向的明细仍在列表中时返回它，否则取最早一条。
     */
    public MediaEpisodeFile pickEpisodeFile(MediaEpisode episode) {
        List<MediaEpisodeFile> files = mediaEpisodeFileMapper.selectList(
                new LambdaQueryWrapper<MediaEpisodeFile>().eq(MediaEpisodeFile::getEpisodeId, episode.getId()));
        return MediaItemVoSupport.pickRepresentative(files, episode.getLastPlayFileId(),
                MediaEpisodeFile::getId, MediaEpisodeFile::getCreateTime);
    }
}
