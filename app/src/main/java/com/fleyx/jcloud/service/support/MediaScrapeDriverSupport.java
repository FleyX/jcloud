package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.MediaRefreshMode;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 削刮链路共享骨架（工单 09）：电影/剧集削刮的同构流程在此单点实现，类型差异经
 * {@link ScrapeStrategy} 注入。独占流程：
 * <ul>
 *   <li>单条刷新四分支（{@link #refreshItem}）：FORCE→manual?refill(true):scrape(force)；
 *       manual→refill(false)；matched+metadataId→enrich/hydrate→apply+persist+complete；其余→scrape(false)。</li>
 *   <li>削刮 force 通道控制流（{@link #scrape}）：TMDB 拉取/匹配失败回退本地优先主流程
 *       （退化为非强制 persist，不清空既有匹配，工单 07）。</li>
 *   <li>本地优先三段式（{@link #scrapeLocalFirst}）：本地 NFO→enrich/merge→apply+persist+complete；
 *       无本地→已匹配行复用/autoMatch，失败 applyUnmatch。</li>
 *   <li>产物补回（{@link #refillArtifacts}）：metadataId 空 return→hydrateRawJson→persist(force)→complete。</li>
 * </ul>
 * 削刮全程非事务：持久化失败由 persist 层容忍（仅标记 persist_status=failed），不阻断主流程；
 * 候选判定 {@link #needScrape} 上移到本驱动与编排方共用一处定义，防两处判定漂移。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaScrapeDriverSupport {

    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final MediaMetadataSupport metadataV2Support;

    /**
     * 是否需要削刮（整库候选判定，工单 09 上移为共享纯函数）：manual 永不覆盖；
     * force 处理全部非 manual，否则只处理「未匹配、不完整或 NFO 缺失」。
     * NFO 存在性纳入候选（工单 05）：NFO 被删除后条目经削刮自动重建；manual 行恒 false，
     * 其 NFO/背景图缺失重建走 refill 分支（persist 路径删跳过后自然重建）。
     */
    public static boolean needScrape(String matchStatus, Boolean metadataComplete, boolean force, boolean nfoMissing) {
        if (MediaMatchStatus.MANUAL.getCode().equals(matchStatus)) {
            return false;
        }
        if (force) {
            return true;
        }
        return MediaMatchStatus.UNMATCHED.getCode().equals(matchStatus)
                || !Boolean.TRUE.equals(metadataComplete) || nfoMissing;
    }

    /**
     * 单条刷新（工单 06/07 四分支）：missing 模式已匹配行复用元数据行——文本缺失由 TMDB 补全
     * （已完整行短路不拉网络）、已有字段不动，产物经 persist IfMissing 校验缺失重建；manual 行只做
     * 产物补回不改字段；未匹配行走完整非强制削刮。force 模式非 manual 行走整库 force 同路径
     * （TMDB 全量覆盖，失败回退本地优先不清空匹配）；manual 行豁免字段覆盖，复用既有元数据行按
     * force 语义全量替换产物。
     */
    public <Row> void refreshItem(ScrapeStrategy<Row> strategy, MediaMetadata metadata, String userId,
                                  MediaRefreshMode mode) {
        Row row = strategy.loadRow(metadata.getOwnerId());
        if (row == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, strategy.entityName() + "不存在");
        }
        if (MediaRefreshMode.FORCE == mode) {
            if (strategy.isManual(row)) {
                refillArtifacts(strategy, row, true);
                return;
            }
            MediaDirectory directory = mediaDirectoryMapper.selectById(strategy.directoryIdOf(row));
            if (directory == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "媒体库不存在");
            }
            scrape(strategy, row, true);
            return;
        }
        if (strategy.isManual(row)) {
            refillArtifacts(strategy, row, false);
            return;
        }
        if (strategy.isMatched(row) && strategy.metadataIdOf(row) != null) {
            MediaMetadata existing = mediaMetadataMapper.selectById(strategy.metadataIdOf(row));
            if (existing != null) {
                // rawJson 水合（工单 09）：enrich 补字段、hydrate 补 rawJson，职责分开；两者都短路时无网络开销
                MediaMetadata enriched = metadataV2Support.enrichLocalWithTmdb(
                        existing, userId, strategy.mediaTypeCode());
                MediaMetadata hydrated = metadataV2Support.hydrateRawJson(
                        enriched, userId, strategy.mediaTypeCode());
                MediaMetadata bound = strategy.applyMatch(row, hydrated, false);
                if (bound != null) {
                    strategy.persist(row, bound, false);
                }
            }
            strategy.refreshComplete(row);
            return;
        }
        MediaDirectory directory = mediaDirectoryMapper.selectById(strategy.directoryIdOf(row));
        if (directory != null) {
            scrape(strategy, row, false);
        }
    }

    /**
     * 单行削刮：force=true 时先入 force 通道（工单 06/07）——有既有元数据且 tmdbId 非空按 ID 重新拉详情、
     * 否则自动匹配，匹配成功则全量覆盖字段并全量替换图片/NFO 产物；拉取/匹配失败不打断、不 unmatch，
     * 回退本地优先主流程（force 自动退化为非强制 persist）。否则本地优先（NFO/本地图片字段优先、
     * 缺失字段由 TMDB 补全；无本地内容时已匹配行复用已有元数据行补产物，未匹配行自动匹配）。
     */
    public <Row> void scrape(ScrapeStrategy<Row> strategy, Row row, boolean force) {
        if (force) {
            MediaMetadata pulled = strategy.pullByTmdbId(row);
            if (pulled != null) {
                MediaMetadata bound = strategy.applyMatch(row, pulled, true);
                if (bound != null) {
                    strategy.persist(row, bound, true);
                }
                strategy.refreshComplete(row);
                return;
            }
            log.debug("强制削刮 TMDB 拉取失败，回退本地优先流程");
        }
        scrapeLocalFirst(strategy, row);
    }

    /**
     * 本地优先主流程（非强制 persist）：本地优先削刮 + TMDB 补全；force 通道失败后同样回退到此
     * （退化为非强制，不清空既有匹配，工单 07）。
     */
    private <Row> void scrapeLocalFirst(ScrapeStrategy<Row> strategy, Row row) {
        MediaMetadata local = strategy.scrapeLocalNfo(row);
        if (local != null) {
            MediaMetadata enriched = strategy.enrichLocal(row, local);
            MediaMetadata bound = strategy.applyMatch(row, enriched, false);
            if (bound != null) {
                strategy.persist(row, bound, false);
            }
            strategy.refreshComplete(row);
            return;
        }
        // 无本地内容：已匹配行复用已有元数据行补产物（不重新搜索，工单 03），未匹配行自动匹配
        MediaMetadata metadata = strategy.isMatched(row) && strategy.metadataIdOf(row) != null
                ? mediaMetadataMapper.selectById(strategy.metadataIdOf(row))
                : null;
        if (metadata == null) {
            metadata = strategy.autoMatch(row);
            if (metadata == null) {
                strategy.applyUnmatch(row);
                return;
            }
        }
        MediaMetadata bound = strategy.applyMatch(row, metadata, false);
        if (bound != null) {
            strategy.persist(row, bound, false);
        }
        strategy.refreshComplete(row);
    }

    /**
     * 已匹配（含 manual）行的图片/NFO 产物补回（工单 03/05/07）：复用已有元数据行的 rawJson 重建缺失产物，
     * 不触碰 matchStatus/metadataId、不重新匹配。无元数据行直接返回；local_nfo 来源同样经 persist 整体
     * 写回（其补全由削刮主路径完成，工单 05）。force=true 时（manual 单条强制刷新，工单 07）按 force
     * 语义全量替换图片/NFO 产物，同样不改字段。
     */
    public <Row> void refillArtifacts(ScrapeStrategy<Row> strategy, Row row, boolean force) {
        String metadataId = strategy.metadataIdOf(row);
        if (metadataId == null) {
            return;
        }
        MediaMetadata metadata = mediaMetadataMapper.selectById(metadataId);
        if (metadata == null) {
            return;
        }
        // rawJson 缺失且已绑定 tmdbId 时先按 ID 水合（工单 09），否则无 rawJson 可取图、产物无法重建
        metadata = metadataV2Support.hydrateRawJson(metadata, strategy.userIdOf(row), strategy.mediaTypeCode());
        strategy.persist(row, metadata, force);
        strategy.refreshComplete(row);
    }
}
