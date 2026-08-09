package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.common.enums.MediaRefreshMode;
import com.fleyx.jcloud.common.enums.MediaType;
import com.fleyx.jcloud.mapper.FileMapper;
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
 * 电影削刮策略实现（工单 09 由 {@code MediaScrapeServiceImpl} 拆分、同构流程下沉
 * {@link MediaScrapeDriverSupport}）：单部电影的本地优先削刮、force 通道、匹配应用与产物补回。
 * 共享流程由驱动独占（{@link MediaScrapeDriverSupport#refreshItem}/{@link MediaScrapeDriverSupport#scrape}/
 * {@link MediaScrapeDriverSupport#refillArtifacts}），本类仅保留电影差异：单层匹配应用、
 * 文件名解析+文件夹名兜底、movie.nfo/同名 nfo/poster/fanart 本地识别链。
 * <p>
 * 本地优先（ADR 0023）：视频同目录存在 NFO 或本地媒体图片时本地字段优先、缺失字段由 TMDB 补全
 * （有 tmdbId 按 ID 拉详情合并，仅本地图片无 NFO 时经自动匹配补文本），合并结果整体写回 NFO；
 * 本地缺失时先文件名解析结果、失败用电影文件夹名兜底。
 * force=true 时先入 force 通道（工单 06/07）：有既有元数据且 tmdbId 非空按 ID 重新拉详情、
 * 否则自动匹配，TMDB 全量覆盖字段并全量替换图片/NFO 产物；拉取/匹配失败回退本地优先主流程
 * （退化为非强制 persist），不清空既有匹配（工单 07）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaMovieScrapeSupport implements ScrapeStrategy<MediaMovie> {

    private final MediaMovieMapper mediaMovieMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final FileMapper fileMapper;
    private final TmdbService tmdbService;
    private final MediaNfoSupport mediaNfoSupport;
    private final MediaMetadataSupport metadataV2Support;
    private final MediaMetadataCompleteSupport completeSupport;
    private final MediaArtworkPersistSupport persistSupport;
    private final MediaPlaybackResolveSupport playbackResolveSupport;
    private final MediaScrapeDriverSupport scrapeDriverSupport;

    @Override
    public MediaMovie loadRow(String ownerId) {
        return mediaMovieMapper.selectById(ownerId);
    }

    @Override
    public String entityName() {
        return "电影";
    }

    @Override
    public String mediaTypeCode() {
        return MediaType.MOVIE.getCode();
    }

    @Override
    public String directoryIdOf(MediaMovie movie) {
        return movie.getDirectoryId();
    }

    @Override
    public String userIdOf(MediaMovie movie) {
        return movie.getUserId();
    }

    @Override
    public String metadataIdOf(MediaMovie movie) {
        return movie.getMetadataId();
    }

    @Override
    public boolean isManual(MediaMovie movie) {
        return MediaMatchStatus.MANUAL.getCode().equals(movie.getMatchStatus());
    }

    @Override
    public boolean isMatched(MediaMovie movie) {
        return MediaMatchStatus.MATCHED.getCode().equals(movie.getMatchStatus());
    }

    /**
     * 单条刷新电影（工单 06/07，四分支流程见 {@link MediaScrapeDriverSupport#refreshItem}）。
     */
    public void refreshMovieItem(MediaMetadata metadata, String userId, MediaRefreshMode mode) {
        scrapeDriverSupport.refreshItem(this, metadata, userId, mode);
    }

    /**
     * 单部电影削刮（force 通道控制流见 {@link MediaScrapeDriverSupport#scrape}）。
     */
    public void scrapeMovie(MediaDirectory directory, MediaMovie movie, boolean force) {
        scrapeDriverSupport.scrape(this, movie, force);
    }

    /**
     * 已匹配（含 manual）电影的图片/NFO 产物补回（见 {@link MediaScrapeDriverSupport#refillArtifacts}）。
     */
    public void refillMovieArtifacts(MediaMovie movie, boolean force) {
        scrapeDriverSupport.refillArtifacts(this, movie, force);
    }

    /**
     * 电影本地优先削刮：优先读取电影文件夹 {@code movie.nfo}，不存在时回退与视频同名的 {@code .nfo}，
     * 本地字段优先、缺失字段由调用方经 {@link MediaMetadataSupport#enrichLocalWithTmdb} 用 TMDB 补全（ADR 0023）；
     * 无 NFO 但存在本地图片（按海报/背景识别链，ADR 0022）时同样本地优先，构建缺失文本字段的
     * local_nfo 元数据（标记不完整），图片绑定本地文件。
     *
     * @return 已绑定 owner 的本地元数据，无 NFO 且无本地图片时返回 null
     */
    @Override
    public MediaMetadata scrapeLocalNfo(MediaMovie movie) {
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
     * 本地元数据 TMDB 补全：有 tmdbId 按 ID 拉详情合并；无 tmdbId（仅本地图片无 NFO 或 NFO 未含
     * tmdbid）时经文件名解析+文件夹名兜底自动匹配补文本，匹配失败维持本地。
     */
    @Override
    public MediaMetadata enrichLocal(MediaMovie movie, MediaMetadata local) {
        if (local.getTmdbId() != null) {
            return metadataV2Support.enrichLocalWithTmdb(local, movie.getUserId(), MediaType.MOVIE.getCode());
        }
        MediaMetadata matched = matchMovieByFile(movie);
        return matched == null ? local : metadataV2Support.mergeLocalWithTmdb(local, matched);
    }

    /**
     * TMDB 自动匹配电影：先按视频文件名解析结果匹配，失败用电影文件夹名兜底
     * （扫描已把文件夹名清理为 movie.title）。
     */
    @Override
    public MediaMetadata autoMatch(MediaMovie movie) {
        return matchMovieByFile(movie);
    }

    /**
     * 电影 force 通道（工单 06/07）：有既有元数据且 tmdbId 非空按 ID 重新拉取详情，否则经文件名/文件夹名
     * 自动匹配；详情拉取异常视为失败（不打断削刮）。
     *
     * @return 全量 TMDB 元数据；拉取/匹配失败返回 null（调用方回退本地优先主流程）
     */
    @Override
    public MediaMetadata pullByTmdbId(MediaMovie movie) {
        MediaMetadata existing = movie.getMetadataId() == null ? null
                : mediaMetadataMapper.selectById(movie.getMetadataId());
        if (existing != null && existing.getTmdbId() != null) {
            try {
                return tmdbService.fetchDetailV2(movie.getUserId(), existing.getTmdbId(),
                        MediaType.MOVIE.getCode());
            } catch (Exception e) {
                log.debug("电影强制削刮拉取详情失败，回退本地优先: movie={}, tmdbId={}, error={}",
                        movie.getId(), existing.getTmdbId(), e.getMessage());
                return null;
            }
        }
        return matchMovieByFile(movie);
    }

    /**
     * 应用电影匹配结果并返回绑定后的元数据行：本地元数据已绑定 owner 直接回写关联；
     * TMDB 游离元数据先绑定 owner 再回写；匹配失败清空元数据行（owner 指针不留孤儿）并置未匹配。
     *
     * @return 绑定后的元数据行；未匹配时返回 null
     */
    @Override
    public MediaMetadata applyMatch(MediaMovie movie, MediaMetadata metadata, boolean force) {
        if (metadata == null) {
            metadataV2Support.deleteByOwner(MediaMetadataOwnerType.MOVIE.getCode(), movie.getId());
            mediaMovieMapper.update(null, new LambdaUpdateWrapper<MediaMovie>()
                    .eq(MediaMovie::getId, movie.getId())
                    .set(MediaMovie::getMetadataId, null)
                    .set(MediaMovie::getMatchStatus, MediaMatchStatus.UNMATCHED.getCode()));
            movie.setMetadataId(null);
            movie.setMatchStatus(MediaMatchStatus.UNMATCHED.getCode());
            return null;
        }
        return bindMovieRow(movie, metadata, MediaMatchStatus.MATCHED.getCode());
    }

    /**
     * 绑定电影行并置匹配状态（metadata 非空；已绑定 owner 直接沿用，游离行经
     * {@link MediaMetadataSupport#upsertByOwner} 绑定）：回写电影行 metadata_id/match_status 并同步实体。
     *
     * @return 绑定后的元数据行
     */
    private MediaMetadata bindMovieRow(MediaMovie movie, MediaMetadata metadata, String matchStatus) {
        MediaMetadata bound = metadata.getOwnerId() == null
                ? metadataV2Support.upsertByOwner(MediaMetadataOwnerType.MOVIE.getCode(), movie.getId(), metadata)
                : metadata;
        mediaMovieMapper.update(null, new LambdaUpdateWrapper<MediaMovie>()
                .eq(MediaMovie::getId, movie.getId())
                .set(MediaMovie::getMetadataId, bound.getId())
                .set(MediaMovie::getMatchStatus, matchStatus));
        movie.setMetadataId(bound.getId());
        movie.setMatchStatus(matchStatus);
        return bound;
    }

    /**
     * 应用电影匹配（TMDB 手动修正/强制刷新共用）：绑定电影行 → 写回 NFO/图片 → 重算完整性。
     *
     * @param movie       电影行
     * @param detached    未绑定 owner 的电影元数据（TMDB 拉取结果）
     * @param matchStatus 电影行匹配状态（matched / manual）
     * @param force       是否强制写回（true 时图片按 rawJson 重新下载覆盖，工单 06）
     * @return 绑定后的元数据行
     */
    public MediaMetadata applyMovieMatchWithPersist(MediaMovie movie, MediaMetadata detached,
                                                    String matchStatus, boolean force) {
        MediaMetadata bound = bindMovieRow(movie, detached, matchStatus);
        persist(movie, bound, force);
        refreshComplete(movie);
        return bound;
    }

    /**
     * 应用电影匹配失败：清空元数据行（owner 指针不留孤儿）置未匹配并重算完整性。
     */
    @Override
    public void applyUnmatch(MediaMovie movie) {
        applyMatch(movie, null, false);
        completeSupport.refreshMovieComplete(movie);
    }

    @Override
    public void persist(MediaMovie movie, MediaMetadata bound, boolean force) {
        persistSupport.persistMovieV2(movie, bound, force);
    }

    @Override
    public void refreshComplete(MediaMovie movie) {
        completeSupport.refreshMovieComplete(movie);
    }

    /**
     * TMDB 自动匹配电影：先按视频文件名解析结果匹配，失败用电影文件夹名兜底
     * （扫描已把文件夹名清理为 movie.title）。
     */
    private MediaMetadata matchMovieByFile(MediaMovie movie) {
        MediaMovieFile file = playbackResolveSupport.pickMovieFile(movie);
        FileNode video = file == null ? null : fileMapper.selectById(file.getFileNodeId());
        if (video == null) {
            return null;
        }
        MediaFileNameParser.ParseResult parsed = MediaFileNameParser.parse(video.getName(), null, null);
        MediaMetadata metadata = tmdbService.autoMatchV2(movie.getUserId(), MediaType.MOVIE.getCode(),
                parsed.title(), parsed.year());
        String fallback = movie.getTitle();
        if (metadata == null && !fallback.isBlank() && !fallback.equals(parsed.title())) {
            metadata = tmdbService.autoMatchV2(movie.getUserId(), MediaType.MOVIE.getCode(),
                    fallback, parsed.year());
        }
        return metadata;
    }

    private MediaNfoSupport.NfoData readNfo(FileNode nfoNode) {
        byte[] bytes = persistSupport.readFileBytes(nfoNode);
        return bytes == null ? null : mediaNfoSupport.parse(new String(bytes, StandardCharsets.UTF_8));
    }
}
