package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaRefreshMode;
import com.fleyx.jcloud.common.enums.MediaType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.service.TmdbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 剧集削刮支撑组件（工单 08 由 {@code MediaScrapeServiceImpl} 拆分）：单部剧集的本地优先削刮、
 * force 通道与产物补回。
 * <p>
 * 本地优先（ADR 0023）：剧文件夹存在 tvshow.nfo/本地图片时本地字段优先、缺失字段由 TMDB 补全
 * （有 tmdbId 按 ID 拉详情，无 tmdbId 经自动匹配补文本，匹配失败维持本地）；本地缺失时已匹配行
 * 复用已有剧级元数据行补产物（派生季/集，工单 03），未匹配行按剧名自动匹配；一次匹配应用到全剧，
 * 季/集元数据按剧级匹配派生（{@link MediaTvScrapeSupport}）。
 * force=true 时先入 force 通道（工单 06/07）：有既有剧级元数据且 tmdbId 非空按 ID 重新拉详情、
 * 否则按剧名自动匹配，成功则派生季/集（全量覆盖）并全量替换写回；拉取/匹配失败回退本地优先主流程
 * （退化为非强制 persist），不清空既有匹配（工单 07）。字段补全经
 * {@link MediaMetadataSupport#enrichLocalWithTmdb}，产物写回经 {@link MediaArtworkPersistV2Support}，
 * 每次削刮结束由 {@link MediaMetadataCompleteSupport} 重算完整性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaSeriesScrapeSupport {

    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final TmdbService tmdbService;
    private final MediaTvScrapeSupport mediaTvScrapeSupport;
    private final MediaMetadataSupport metadataV2Support;
    private final MediaMetadataCompleteSupport completeSupport;
    private final MediaArtworkPersistV2Support artworkPersistV2Support;

    /**
     * 单条刷新剧集（工单 06/07）：missing 模式已匹配行复用剧级元数据行——文本缺失由 TMDB 补全（已完整行
     * 短路不拉网络）、已有字段不动，经派生季/集（非强制合并）与 persist IfMissing 重建缺失产物；
     * manual 行只做产物补回不改字段；未匹配行走完整非强制削刮。force 模式非 manual 行走整库 force 同路径
     * （剧级详情全量覆盖，失败回退本地优先不清空匹配）；manual 行豁免字段覆盖，复用既有元数据行按 force
     * 语义全量替换产物。
     */
    public void refreshSeriesItem(MediaMetadata metadata, String userId, MediaRefreshMode mode) {
        MediaSeries series = mediaSeriesMapper.selectById(metadata.getOwnerId());
        if (series == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "电视剧不存在");
        }
        if (MediaRefreshMode.FORCE == mode) {
            if (MediaMatchStatus.MANUAL.getCode().equals(series.getMatchStatus())) {
                refillSeriesArtifacts(series, true);
                return;
            }
            MediaDirectory directory = mediaDirectoryMapper.selectById(series.getDirectoryId());
            if (directory == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "媒体库不存在");
            }
            scrapeSeriesItem(directory, series, true);
            return;
        }
        if (MediaMatchStatus.MANUAL.getCode().equals(series.getMatchStatus())) {
            refillSeriesArtifacts(series, false);
            return;
        }
        if (MediaMatchStatus.MATCHED.getCode().equals(series.getMatchStatus()) && series.getMetadataId() != null) {
            MediaMetadata existing = mediaMetadataMapper.selectById(series.getMetadataId());
            if (existing != null) {
                MediaMetadata enriched = metadataV2Support.enrichLocalWithTmdb(existing, userId, MediaType.TV.getCode());
                // rawJson 水合（工单 09）：enrich 补字段、hydrate 补 rawJson，职责分开；两者都短路时无网络开销
                MediaMetadata hydrated = metadataV2Support.hydrateRawJson(enriched, series.getUserId(), MediaType.TV.getCode());
                mediaTvScrapeSupport.applySeriesMatchWithDerivation(series, hydrated,
                        MediaMatchStatus.MATCHED.getCode(), false);
            } else {
                completeSupport.refreshSeriesComplete(series);
            }
            return;
        }
        MediaDirectory directory = mediaDirectoryMapper.selectById(series.getDirectoryId());
        if (directory != null) {
            scrapeSeriesItem(directory, series, false);
        }
    }

    /**
     * 单部剧集削刮：force=true 时先入 force 通道（工单 06/07）：有既有剧级元数据且 tmdbId 非空按 ID
     * 重新拉详情、否则按剧名自动匹配，成功则派生季/集（全量覆盖）并全量替换写回；拉取/匹配失败不打断、
     * 不 unmatch，回退本地优先主流程（退化为非强制 persist）。否则本地优先（tvshow.nfo/本地图片）字段优先、
     * 缺失字段由 TMDB 补全；无本地内容时已匹配行复用已有剧级元数据行补产物（派生季/集，工单 03），
     * 未匹配行按剧名自动匹配。
     */
    public void scrapeSeriesItem(MediaDirectory directory, MediaSeries series, boolean force) {
        if (force) {
            MediaMetadata pulled = pullTmdbForForce(directory, series);
            if (pulled != null) {
                mediaTvScrapeSupport.applySeriesMatchWithDerivation(series, pulled,
                        MediaMatchStatus.MATCHED.getCode(), true);
                return;
            }
            log.debug("剧集强制削刮 TMDB 拉取失败，回退本地优先流程: series={}", series.getSeriesName());
        }
        scrapeSeriesLocalFirst(directory, series);
    }

    /**
     * 剧集本地优先主流程（非强制 persist）：本地优先削刮 + TMDB 补全；force 通道失败后同样回退到此
     * （退化为非强制，不清空既有匹配，工单 07）。
     */
    private void scrapeSeriesLocalFirst(MediaDirectory directory, MediaSeries series) {
        // 本地优先：剧文件夹存在 tvshow.nfo/本地图片时本地字段优先，缺失字段由 TMDB 补全
        MediaMetadata localMetadata = mediaTvScrapeSupport.scrapeSeriesLocalNfo(series);
        if (localMetadata != null) {
            localMetadata = enrichLocalSeries(localMetadata, directory.getUserId(), series);
            mediaTvScrapeSupport.applyLocalSeriesMatch(series, localMetadata);
            return;
        }
        // 已匹配行无本地内容时复用已有剧级元数据行补产物（派生季/集），不重新搜索（工单 03）
        MediaMetadata metadata = MediaMatchStatus.MATCHED.getCode().equals(series.getMatchStatus())
                && series.getMetadataId() != null ? mediaMetadataMapper.selectById(series.getMetadataId()) : null;
        if (metadata == null) {
            metadata = tmdbService.autoMatchV2(directory.getUserId(), MediaType.TV.getCode(),
                    series.getSeriesName(), series.getReleaseYear());
            if (metadata == null) {
                mediaTvScrapeSupport.applySeriesUnmatch(series);
                return;
            }
        }
        mediaTvScrapeSupport.applySeriesMatchWithDerivation(series, metadata,
                MediaMatchStatus.MATCHED.getCode(), false);
    }

    /**
     * 剧集 force 通道（工单 06/07）：有既有剧级元数据且 tmdbId 非空按 ID 重新拉取剧级详情，拉取失败
     * （返回 null 或抛异常）回退按剧名自动匹配；详情拉取异常视为失败（不打断削刮）。
     *
     * @return 全量 TMDB 剧级元数据；拉取/匹配均失败返回 null（调用方回退本地优先主流程）
     */
    private MediaMetadata pullTmdbForForce(MediaDirectory directory, MediaSeries series) {
        MediaMetadata existing = series.getMetadataId() == null ? null
                : mediaMetadataMapper.selectById(series.getMetadataId());
        MediaMetadata metadata = null;
        if (existing != null && existing.getTmdbId() != null) {
            try {
                metadata = tmdbService.fetchDetailV2(directory.getUserId(), existing.getTmdbId(),
                        MediaType.TV.getCode());
            } catch (Exception e) {
                log.debug("剧集强制削刮拉取详情失败，回退按剧名自动匹配: series={}, tmdbId={}, error={}",
                        series.getSeriesName(), existing.getTmdbId(), e.getMessage());
            }
        }
        return metadata != null ? metadata : tmdbService.autoMatchV2(directory.getUserId(), MediaType.TV.getCode(),
                series.getSeriesName(), series.getReleaseYear());
    }

    /**
     * 剧集本地元数据 TMDB 补全（ADR 0023）：有 tmdbId 按 ID 拉详情合并（本地字段优先、缺失字段补齐、
     * rawJson 恒取远端）；无 tmdbId（仅本地图片 / tvshow.nfo 未含 tmdbid）时经自动匹配补文本，
     * 匹配失败维持现状。图片绑定始终沿用本地文件。
     */
    private MediaMetadata enrichLocalSeries(MediaMetadata local, String userId, MediaSeries series) {
        if (local == null) {
            return null;
        }
        if (local.getTmdbId() != null) {
            return metadataV2Support.enrichLocalWithTmdb(local, userId, MediaType.TV.getCode());
        }
        MediaMetadata matched;
        try {
            matched = tmdbService.autoMatchV2(userId, MediaType.TV.getCode(),
                    series.getSeriesName(), series.getReleaseYear());
        } catch (Exception e) {
            log.debug("剧集本地元数据自动匹配补全失败，维持本地: series={}, error={}",
                    series.getSeriesName(), e.getMessage());
            return local;
        }
        if (matched == null) {
            return local;
        }
        return metadataV2Support.mergeLocalWithTmdb(local, matched);
    }

    /**
     * 已匹配（含 manual）剧集的图片/NFO 产物补回（工单 03/05/07）：复用已有剧级元数据行重建缺失产物
     * （剧海报/背景、季海报、集剧照与各级 NFO），不触碰 matchStatus/metadataId、不重新匹配。
     * 无元数据行直接返回；local_nfo 来源同样经 persistSeriesV2 整体写回（其补全由削刮主路径完成，工单 05）。
     * force=true 时（manual 单条强制刷新，工单 07）按 force 语义全量替换产物，同样不改字段。
     */
    public void refillSeriesArtifacts(MediaSeries series, boolean force) {
        if (series.getMetadataId() == null) {
            return;
        }
        MediaMetadata metadata = mediaMetadataMapper.selectById(series.getMetadataId());
        if (metadata == null) {
            return;
        }
        // rawJson 缺失且已绑定 tmdbId 时先按 ID 水合（工单 09），否则无 rawJson 可取图、剧级产物无法重建
        metadata = metadataV2Support.hydrateRawJson(metadata, series.getUserId(), MediaType.TV.getCode());
        artworkPersistV2Support.persistSeriesV2(series, metadata, force);
        completeSupport.refreshSeriesComplete(series);
    }
}
