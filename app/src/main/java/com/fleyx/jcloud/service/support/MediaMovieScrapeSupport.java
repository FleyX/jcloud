package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.common.enums.MediaRefreshMode;
import com.fleyx.jcloud.common.enums.MediaType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.util.MediaFileNameParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 电影削刮支撑组件（工单 08 由 {@code MediaScrapeServiceImpl} 拆分）：单部电影的本地优先削刮、
 * force 通道、匹配应用与产物补回。
 * <p>
 * 本地优先（ADR 0023）：视频同目录存在 NFO 或本地媒体图片时本地字段优先、缺失字段由 TMDB 补全
 * （有 tmdbId 按 ID 拉详情合并，仅本地图片无 NFO 时经自动匹配补文本），合并结果整体写回 NFO；
 * 本地缺失时先文件名解析结果、失败用电影文件夹名兜底。
 * force=true 时先入 force 通道（工单 06/07）：有既有元数据且 tmdbId 非空按 ID 重新拉详情、
 * 否则自动匹配，TMDB 全量覆盖字段并全量替换图片/NFO 产物；拉取/匹配失败回退本地优先主流程
 * （退化为非强制 persist），不清空既有匹配（工单 07）。字段补全经
 * {@link MediaMetadataSupport#enrichLocalWithTmdb}，产物写回经 {@link MediaArtworkPersistV2Support}，
 * 每次削刮结束由 {@link MediaMetadataCompleteSupport} 重算完整性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaMovieScrapeSupport {

    private final MediaMovieMapper mediaMovieMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final FileMapper fileMapper;
    private final TmdbService tmdbService;
    private final MediaNfoSupport mediaNfoSupport;
    private final MediaMetadataSupport metadataV2Support;
    private final MediaMetadataCompleteSupport completeSupport;
    private final MediaArtworkPersistSupport persistSupport;
    private final MediaArtworkPersistV2Support artworkPersistV2Support;
    private final MediaPlaybackResolveSupport playbackResolveSupport;

    /**
     * 单条刷新电影（工单 06/07）：missing 模式已匹配行复用元数据行——文本缺失由 TMDB 补全（已完整行
     * 方法内短路不拉网络）、已有字段不动，产物经 persist IfMissing 校验缺失重建；manual 行只做产物补回
     * 不改字段；未匹配行走完整非强制削刮。force 模式非 manual 行走整库 force 同路径（TMDB 全量覆盖，
     * 失败回退本地优先不清空匹配）；manual 行豁免字段覆盖，复用既有元数据行按 force 语义全量替换产物。
     */
    public void refreshMovieItem(MediaMetadata metadata, String userId, MediaRefreshMode mode) {
        MediaMovie movie = mediaMovieMapper.selectById(metadata.getOwnerId());
        if (movie == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "电影不存在");
        }
        if (MediaRefreshMode.FORCE == mode) {
            if (MediaMatchStatus.MANUAL.getCode().equals(movie.getMatchStatus())) {
                refillMovieArtifacts(movie, true);
                return;
            }
            MediaDirectory directory = mediaDirectoryMapper.selectById(movie.getDirectoryId());
            if (directory == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "媒体库不存在");
            }
            scrapeMovie(directory, movie, true);
            return;
        }
        if (MediaMatchStatus.MANUAL.getCode().equals(movie.getMatchStatus())) {
            refillMovieArtifacts(movie, false);
            return;
        }
        if (MediaMatchStatus.MATCHED.getCode().equals(movie.getMatchStatus()) && movie.getMetadataId() != null) {
            MediaMetadata existing = mediaMetadataMapper.selectById(movie.getMetadataId());
            if (existing != null) {
                MediaMetadata enriched = metadataV2Support.enrichLocalWithTmdb(existing, userId, MediaType.MOVIE.getCode());
                MediaMetadata bound = applyMovieMatch(movie, enriched);
                if (bound != null) {
                    artworkPersistV2Support.persistMovieV2(movie, bound, false);
                }
            }
            completeSupport.refreshMovieComplete(movie);
            return;
        }
        MediaDirectory directory = mediaDirectoryMapper.selectById(movie.getDirectoryId());
        if (directory != null) {
            scrapeMovie(directory, movie, false);
        }
    }

    /**
     * 单部电影削刮：本地优先（movie.nfo 优先、同名 .nfo 兜底 / poster / fanart），本地内容存在时本地字段优先、
     * 缺失字段由 TMDB 补全（有 tmdbId 按 ID 拉详情，仅本地图片无 NFO 时经自动匹配补文本）；无本地内容时 TMDB
     * 先文件名解析结果、失败用电影文件夹名兜底；已匹配行无本地内容时复用已有元数据行补产物，不重新搜索。
     * force=true 时先入 force 通道（工单 06/07）：有既有元数据且 tmdbId 非空按 ID 重新拉详情、否则自动匹配，
     * 匹配成功则全量覆盖字段并全量替换图片/NFO 产物；拉取/匹配失败不打断、不 unmatch，回退本地优先主流程
     * （force 自动退化为非强制 persist）。
     */
    public void scrapeMovie(MediaDirectory directory, MediaMovie movie, boolean force) {
        if (force) {
            MediaMetadata pulled = pullTmdbForForce(directory, movie);
            if (pulled != null) {
                MediaMetadata bound = applyMovieMatch(movie, pulled);
                if (bound != null) {
                    artworkPersistV2Support.persistMovieV2(movie, bound, true);
                }
                completeSupport.refreshMovieComplete(movie);
                return;
            }
            log.debug("电影强制削刮 TMDB 拉取失败，回退本地优先流程: movie={}", movie.getId());
        }
        scrapeMovieLocalFirst(directory, movie);
    }

    /**
     * 电影本地优先主流程（非强制 persist）：本地优先削刮 + TMDB 补全；force 通道失败后同样回退到此
     * （退化为非强制，不清空既有匹配，工单 07）。
     */
    private void scrapeMovieLocalFirst(MediaDirectory directory, MediaMovie movie) {
        MediaMetadata local = scrapeMovieLocalNfo(movie);
        MediaMetadata metadata = local;
        if (metadata != null && metadata.getTmdbId() != null) {
            metadata = metadataV2Support.enrichLocalWithTmdb(metadata, directory.getUserId(), MediaType.MOVIE.getCode());
        }
        if (metadata == null) {
            if (MediaMatchStatus.MATCHED.getCode().equals(movie.getMatchStatus())
                    && movie.getMetadataId() != null) {
                metadata = mediaMetadataMapper.selectById(movie.getMetadataId());
            }
            if (metadata == null) {
                metadata = matchMovieByFile(directory, movie);
            }
        } else if (metadata.getTmdbId() == null) {
            // 本地仅图片无 NFO 或 NFO 未含 tmdbid：文本字段由 TMDB 自动匹配补全，图片沿用本地
            MediaMetadata matched = matchMovieByFile(directory, movie);
            if (matched != null) {
                metadata = metadataV2Support.mergeLocalWithTmdb(metadata, matched);
            }
        }
        MediaMetadata bound = applyMovieMatch(movie, metadata);
        if (bound != null) {
            artworkPersistV2Support.persistMovieV2(movie, bound, false);
        }
        completeSupport.refreshMovieComplete(movie);
    }

    /**
     * 电影 force 通道（工单 06/07）：有既有元数据且 tmdbId 非空按 ID 重新拉取详情，否则经文件名/文件夹名
     * 自动匹配；详情拉取异常视为失败（不打断削刮）。
     *
     * @return 全量 TMDB 元数据；拉取/匹配失败返回 null（调用方回退本地优先主流程）
     */
    private MediaMetadata pullTmdbForForce(MediaDirectory directory, MediaMovie movie) {
        MediaMetadata existing = movie.getMetadataId() == null ? null
                : mediaMetadataMapper.selectById(movie.getMetadataId());
        if (existing != null && existing.getTmdbId() != null) {
            try {
                return tmdbService.fetchDetailV2(directory.getUserId(), existing.getTmdbId(),
                        MediaType.MOVIE.getCode());
            } catch (Exception e) {
                log.debug("电影强制削刮拉取详情失败，回退本地优先: movie={}, tmdbId={}, error={}",
                        movie.getId(), existing.getTmdbId(), e.getMessage());
                return null;
            }
        }
        return matchMovieByFile(directory, movie);
    }

    /**
     * TMDB 自动匹配电影：先按视频文件名解析结果匹配，失败用电影文件夹名兜底
     * （扫描已把文件夹名清理为 movie.title）。
     */
    private MediaMetadata matchMovieByFile(MediaDirectory directory, MediaMovie movie) {
        MediaMovieFile file = playbackResolveSupport.pickMovieFile(movie);
        FileNode video = file == null ? null : fileMapper.selectById(file.getFileNodeId());
        if (video == null) {
            return null;
        }
        MediaFileNameParser.ParseResult parsed = MediaFileNameParser.parse(video.getName(), null, null);
        MediaMetadata metadata = tmdbService.autoMatchV2(directory.getUserId(), MediaType.MOVIE.getCode(),
                parsed.title(), parsed.year());
        String fallback = movie.getTitle();
        if (metadata == null && !fallback.isBlank() && !fallback.equals(parsed.title())) {
            metadata = tmdbService.autoMatchV2(directory.getUserId(), MediaType.MOVIE.getCode(),
                    fallback, parsed.year());
        }
        return metadata;
    }

    /**
     * 电影本地优先削刮：优先读取电影文件夹 {@code movie.nfo}，不存在时回退与视频同名的 {@code .nfo}，
     * 本地字段优先、缺失字段由调用方经 {@link MediaMetadataSupport#enrichLocalWithTmdb} 用 TMDB 补全（ADR 0023）；
     * 无 NFO 但存在本地图片（按海报/背景识别链，ADR 0022）时同样本地优先，构建缺失文本字段的
     * local_nfo 元数据（标记不完整），图片绑定本地文件。
     *
     * @return 已绑定 owner 的本地元数据，无 NFO 且无本地图片时返回 null
     */
    private MediaMetadata scrapeMovieLocalNfo(MediaMovie movie) {
        FileNode folder = fileMapper.selectById(movie.getFolderNodeId());
        MediaMovieFile file = playbackResolveSupport.pickMovieFile(movie);
        FileNode video = file == null ? null : fileMapper.selectById(file.getFileNodeId());
        if (folder == null || video == null) {
            return null;
        }
        String userId = movie.getUserId();
        FileNode nfoNode = persistSupport.findFirstChildFile(userId, folder.getId(),
                List.of(MediaNfoSupport.MOVIE_NFO, mediaNfoSupport.nfoNameOf(video.getName())));
        MediaNfoSupport.NfoData data = nfoNode == null ? null : readNfo(nfoNode);
        FileNode poster = persistSupport.findFirstChildFile(userId, folder.getId(), MediaNfoSupport.MOVIE_POSTER_NAMES);
        FileNode fanart = persistSupport.findFirstChildFile(userId, folder.getId(), MediaNfoSupport.BACKDROP_NAMES);
        if (data == null && poster == null && fanart == null) {
            return null;
        }
        return metadataV2Support.upsertLocal(MediaMetadataOwnerType.MOVIE.getCode(), movie.getId(), userId,
                data == null ? MediaNfoSupport.emptyData(MediaType.MOVIE.getCode()) : data,
                poster == null ? null : poster.getId(), fanart == null ? null : fanart.getId());
    }

    /**
     * 应用电影匹配结果并返回绑定后的元数据行：本地元数据已绑定 owner 直接回写关联；
     * TMDB 游离元数据先绑定 owner 再回写；匹配失败清空元数据行（owner 指针不留孤儿）并置未匹配。
     *
     * @return 绑定后的元数据行；未匹配时返回 null
     */
    private MediaMetadata applyMovieMatch(MediaMovie movie, MediaMetadata metadata) {
        String metadataId;
        String matchStatus;
        MediaMetadata bound;
        if (metadata == null) {
            metadataV2Support.deleteByOwner(MediaMetadataOwnerType.MOVIE.getCode(), movie.getId());
            metadataId = null;
            matchStatus = MediaMatchStatus.UNMATCHED.getCode();
            bound = null;
        } else {
            bound = metadata.getOwnerId() == null
                    ? metadataV2Support.upsertByOwner(MediaMetadataOwnerType.MOVIE.getCode(), movie.getId(), metadata)
                    : metadata;
            metadataId = bound.getId();
            matchStatus = MediaMatchStatus.MATCHED.getCode();
        }
        mediaMovieMapper.update(null, new LambdaUpdateWrapper<MediaMovie>()
                .eq(MediaMovie::getId, movie.getId())
                .set(MediaMovie::getMetadataId, metadataId)
                .set(MediaMovie::getMatchStatus, matchStatus));
        movie.setMetadataId(metadataId);
        movie.setMatchStatus(matchStatus);
        return bound;
    }

    /**
     * 已匹配（含 manual）行的图片/NFO 产物补回（工单 03/05/07）：复用已有元数据行的 rawJson 重建缺失产物，
     * 不触碰 matchStatus/metadataId、不重新匹配。无元数据行直接返回；local_nfo 来源同样经
     * persistMovieV2 整体写回（其补全由削刮主路径完成，工单 05）。
     * force=true 时（manual 单条强制刷新，工单 07）按 force 语义全量替换图片/NFO 产物，同样不改字段。
     */
    public void refillMovieArtifacts(MediaMovie movie, boolean force) {
        if (movie.getMetadataId() == null) {
            return;
        }
        MediaMetadata metadata = mediaMetadataMapper.selectById(movie.getMetadataId());
        if (metadata == null) {
            return;
        }
        artworkPersistV2Support.persistMovieV2(movie, metadata, force);
        completeSupport.refreshMovieComplete(movie);
    }

    private MediaNfoSupport.NfoData readNfo(FileNode nfoNode) {
        byte[] bytes = persistSupport.readFileBytes(nfoNode);
        return bytes == null ? null : mediaNfoSupport.parse(new String(bytes, StandardCharsets.UTF_8));
    }
}
