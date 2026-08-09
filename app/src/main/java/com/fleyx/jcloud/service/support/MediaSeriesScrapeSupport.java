package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaRefreshMode;
import com.fleyx.jcloud.common.enums.MediaType;
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
 * 剧集削刮策略实现（工单 09 由 {@code MediaScrapeServiceImpl} 拆分、同构流程下沉
 * {@link MediaScrapeDriverSupport}）：单部剧集的本地优先削刮、force 通道与产物补回。
 * 共享流程由驱动独占（{@link MediaScrapeDriverSupport#refreshItem}/{@link MediaScrapeDriverSupport#scrape}/
 * {@link MediaScrapeDriverSupport#refillArtifacts}），本类仅保留剧集差异：剧名+年份自动匹配、
 * 以剧为单位应用（季/集按剧级匹配派生、清三级），深层逻辑委托 {@link MediaTvScrapeSupport}。
 * <p>
 * 本地优先（ADR 0023）：剧文件夹存在 tvshow.nfo/本地图片时本地字段优先、缺失字段由 TMDB 补全
 * （有 tmdbId 按 ID 拉详情，无 tmdbId 经自动匹配补文本，匹配失败维持本地）；本地缺失时已匹配行
 * 复用已有剧级元数据行补产物（派生季/集，工单 03），未匹配行按剧名自动匹配；一次匹配应用到全剧。
 * force=true 时先入 force 通道（工单 06/07）：有既有剧级元数据且 tmdbId 非空按 ID 重新拉详情、
 * 否则按剧名自动匹配，成功则派生季/集（全量覆盖）并全量替换写回；拉取/匹配失败回退本地优先主流程
 * （退化为非强制 persist），不清空既有匹配（工单 07）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaSeriesScrapeSupport implements ScrapeStrategy<MediaSeries> {

    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final TmdbService tmdbService;
    private final MediaTvScrapeSupport mediaTvScrapeSupport;
    private final MediaMetadataSupport metadataV2Support;
    private final MediaMetadataCompleteSupport completeSupport;
    private final MediaArtworkPersistV2Support artworkPersistV2Support;
    private final MediaScrapeDriverSupport scrapeDriverSupport;

    @Override
    public MediaSeries loadRow(String ownerId) {
        return mediaSeriesMapper.selectById(ownerId);
    }

    @Override
    public String entityName() {
        return "电视剧";
    }

    @Override
    public String mediaTypeCode() {
        return MediaType.TV.getCode();
    }

    @Override
    public String directoryIdOf(MediaSeries series) {
        return series.getDirectoryId();
    }

    @Override
    public String userIdOf(MediaSeries series) {
        return series.getUserId();
    }

    @Override
    public String metadataIdOf(MediaSeries series) {
        return series.getMetadataId();
    }

    @Override
    public boolean isManual(MediaSeries series) {
        return MediaMatchStatus.MANUAL.getCode().equals(series.getMatchStatus());
    }

    @Override
    public boolean isMatched(MediaSeries series) {
        return MediaMatchStatus.MATCHED.getCode().equals(series.getMatchStatus());
    }

    /**
     * 单条刷新剧集（工单 06/07，四分支流程见 {@link MediaScrapeDriverSupport#refreshItem}）。
     */
    public void refreshSeriesItem(MediaMetadata metadata, String userId, MediaRefreshMode mode) {
        scrapeDriverSupport.refreshItem(this, metadata, userId, mode);
    }

    /**
     * 单部剧集削刮（force 通道控制流见 {@link MediaScrapeDriverSupport#scrape}）。
     */
    public void scrapeSeriesItem(MediaDirectory directory, MediaSeries series, boolean force) {
        scrapeDriverSupport.scrape(this, series, force);
    }

    /**
     * 已匹配（含 manual）剧集的图片/NFO 产物补回（见 {@link MediaScrapeDriverSupport#refillArtifacts}）。
     */
    public void refillSeriesArtifacts(MediaSeries series, boolean force) {
        scrapeDriverSupport.refillArtifacts(this, series, force);
    }

    /**
     * 剧集本地优先削刮（tvshow.nfo/季海报/逐集 NFO 绑定，深层逻辑见 {@link MediaTvScrapeSupport#scrapeSeriesLocalNfo}）。
     */
    @Override
    public MediaMetadata scrapeLocalNfo(MediaSeries series) {
        return mediaTvScrapeSupport.scrapeSeriesLocalNfo(series);
    }

    /**
     * 剧集本地元数据 TMDB 补全（ADR 0023）：有 tmdbId 按 ID 拉详情合并（本地字段优先、缺失字段补齐、
     * rawJson 恒取远端）；无 tmdbId（仅本地图片 / tvshow.nfo 未含 tmdbid）时经自动匹配补文本，
     * 匹配失败维持现状。图片绑定始终沿用本地文件。
     */
    @Override
    public MediaMetadata enrichLocal(MediaSeries series, MediaMetadata local) {
        return enrichLocalSeries(local, series.getUserId(), series);
    }

    /**
     * 剧集 TMDB 自动匹配：按剧名+年份匹配。
     */
    @Override
    public MediaMetadata autoMatch(MediaSeries series) {
        return tmdbService.autoMatchV2(series.getUserId(), MediaType.TV.getCode(),
                series.getSeriesName(), series.getReleaseYear());
    }

    /**
     * 剧集 force 通道（工单 06/07）：有既有剧级元数据且 tmdbId 非空按 ID 重新拉取剧级详情，拉取失败
     * （返回 null 或抛异常）回退按剧名自动匹配；详情拉取异常视为失败（不打断削刮）。
     *
     * @return 全量 TMDB 剧级元数据；拉取/匹配均失败返回 null（调用方回退本地优先主流程）
     */
    @Override
    public MediaMetadata pullByTmdbId(MediaSeries series) {
        MediaMetadata existing = series.getMetadataId() == null ? null
                : mediaMetadataMapper.selectById(series.getMetadataId());
        MediaMetadata metadata = null;
        if (existing != null && existing.getTmdbId() != null) {
            try {
                metadata = tmdbService.fetchDetailV2(series.getUserId(), existing.getTmdbId(),
                        MediaType.TV.getCode());
            } catch (Exception e) {
                log.debug("剧集强制削刮拉取详情失败，回退按剧名自动匹配: series={}, tmdbId={}, error={}",
                        series.getSeriesName(), existing.getTmdbId(), e.getMessage());
            }
        }
        return metadata != null ? metadata : tmdbService.autoMatchV2(series.getUserId(), MediaType.TV.getCode(),
                series.getSeriesName(), series.getReleaseYear());
    }

    /**
     * 应用剧级匹配并绑定 owner：绑定剧行 → 派生季/集元数据（深层逻辑见
     * {@link MediaTvScrapeSupport#bindSeriesMatch}），写回与完整性重算由驱动续接。
     */
    @Override
    public MediaMetadata applyMatch(MediaSeries series, MediaMetadata metadata, boolean force) {
        return mediaTvScrapeSupport.bindSeriesMatch(series, metadata,
                MediaMatchStatus.MATCHED.getCode(), force);
    }

    /**
     * 应用剧级匹配失败：清空剧/季/集全部元数据行与关联（owner 指针不留孤儿），置未匹配并重算完整性
     * （深层逻辑见 {@link MediaTvScrapeSupport#applySeriesUnmatch}）。
     */
    @Override
    public void applyUnmatch(MediaSeries series) {
        mediaTvScrapeSupport.applySeriesUnmatch(series);
    }

    @Override
    public void persist(MediaSeries series, MediaMetadata bound, boolean force) {
        artworkPersistV2Support.persistSeriesV2(series, bound, force);
    }

    @Override
    public void refreshComplete(MediaSeries series) {
        completeSupport.refreshSeriesComplete(series);
    }

    /**
     * 剧集本地元数据 TMDB 补全（ADR 0023 合并语义）：有 tmdbId 按 ID 拉详情合并（本地字段优先、缺失字段补齐、
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
}
