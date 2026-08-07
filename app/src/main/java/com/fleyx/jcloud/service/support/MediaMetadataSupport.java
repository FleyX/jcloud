package com.fleyx.jcloud.service.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaMetadataSource;
import com.fleyx.jcloud.common.enums.MediaPersistStatus;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.service.TmdbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 新模型元数据行（t_media_metadata）生命周期支撑组件（ADR 0021 / issue #20）。
 * <p>
 * 电影/剧集/季/集行各持 metadata_id 一对一关联元数据行，元数据行以 owner_type + owner_id
 * 反向指针同步维护：写入时设置（{@link #upsertByOwner} 按 owner 定位，已存在则原地更新，
 * 否则新建并写入反向指针），删除时由级联支撑组件按 owner 定位清理（{@link #deleteByOwner}）。
 * TMDB 拉取结果（未落库的游离元数据）与本地 NFO 解析结果均经本组件绑定 owner 后落库；
 * 本地优先削刮的字段补全（{@link #enrichLocalWithTmdb}）同样收口在本组件（工单 08 拆分）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaMetadataSupport {

    private final MediaMetadataMapper mediaMetadataMapper;
    private final MediaMetadataCompleteSupport completeSupport;
    private final TmdbService tmdbService;

    /**
     * 按 owner 反向指针 upsert 元数据行：已存在对应行则原地更新（来源也随新数据变化），
     * 否则新建并写入 owner 指针与默认落盘状态。返回绑定后的元数据行（不修改入参 data）。
     *
     * @param ownerType 归属实体类型（{@link com.fleyx.jcloud.common.enums.MediaMetadataOwnerType}）
     * @param ownerId   归属实体 ID
     * @param data      待绑定元数据（需含 userId 与全部字段）
     */
    public MediaMetadata upsertByOwner(String ownerType, String ownerId, MediaMetadata data) {
        MediaMetadata existing = selectByOwner(ownerType, ownerId);
        if (existing == null) {
            existing = new MediaMetadata();
            existing.setUserId(data.getUserId());
            existing.setOwnerType(ownerType);
            existing.setOwnerId(ownerId);
            existing.setPersistStatus(data.getPersistStatus() == null
                    ? MediaPersistStatus.PENDING.getCode() : data.getPersistStatus());
            copyMetadata(data, existing);
            mediaMetadataMapper.insert(existing);
            return existing;
        }
        copyMetadata(data, existing);
        mediaMetadataMapper.updateById(existing);
        return existing;
    }

    /**
     * 本地优先削刮构建 local_nfo 元数据行并绑定 owner：字段以 NFO/本地图片为准，
     * 缺失字段由调用方经 {@link #mergeLocalWithTmdb} 用 TMDB 补全（ADR 0023），
     * 图片绑定本地文件沿用不覆盖。
     *
     * @param ownerType      归属实体类型
     * @param ownerId        归属实体 ID
     * @param userId         用户 ID
     * @param data           NFO 解析结果（无 NFO 时为 {@link MediaNfoSupport#emptyData} 空数据）
     * @param posterNodeId   海报图文件节点 ID，可为空
     * @param backdropNodeId 背景图文件节点 ID，可为空
     */
    public MediaMetadata upsertLocal(String ownerType, String ownerId, String userId,
                                       MediaNfoSupport.NfoData data, String posterNodeId, String backdropNodeId) {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setUserId(userId);
        metadata.setSource(MediaMetadataSource.LOCAL_NFO.getCode());
        metadata.setTmdbId(data.tmdbId());
        metadata.setTitle(data.title());
        metadata.setOriginalTitle(data.originalTitle());
        metadata.setOverview(data.overview());
        metadata.setReleaseDate(data.releaseDate());
        metadata.setVoteAverage(data.voteAverage());
        metadata.setGenres(data.genres() == null || data.genres().isBlank() ? null : data.genres());
        metadata.setPosterFileNodeId(posterNodeId);
        metadata.setBackdropFileNodeId(backdropNodeId);
        metadata.setPersistStatus(MediaPersistStatus.PENDING.getCode());
        return upsertByOwner(ownerType, ownerId, metadata);
    }

    /**
     * 本地优先合并语义（ADR 0023）：本地非空字段优先，缺失字段用 TMDB 远端值补齐，
     * {@code rawJson} 恒取远端（图片写回需要）；source/owner 指针与图片绑定保持本地不变。
     * 原地修改 local 并返回（remote 为游离 TMDB 元数据或已绑定行均可）。
     */
    public MediaMetadata mergeLocalWithTmdb(MediaMetadata local, MediaMetadata remote) {
        if (local == null || remote == null) {
            return local;
        }
        if (local.getTmdbId() == null) {
            local.setTmdbId(remote.getTmdbId());
        }
        if (StrUtil.isBlank(local.getTitle())) {
            local.setTitle(remote.getTitle());
        }
        if (StrUtil.isBlank(local.getOriginalTitle())) {
            local.setOriginalTitle(remote.getOriginalTitle());
        }
        if (StrUtil.isBlank(local.getOverview())) {
            local.setOverview(remote.getOverview());
        }
        if (StrUtil.isBlank(local.getReleaseDate())) {
            local.setReleaseDate(remote.getReleaseDate());
        }
        if (local.getVoteAverage() == null) {
            local.setVoteAverage(remote.getVoteAverage());
        }
        if (StrUtil.isBlank(local.getGenres())) {
            local.setGenres(remote.getGenres());
        }
        local.setRawJson(remote.getRawJson());
        return local;
    }

    /**
     * 本地元数据 TMDB 补全（ADR 0023 合并语义，工单 08 由削刮实现收口到本组件）：local 为 null、
     * tmdbId 为空或本地已完整（5 项齐备）时原样返回（方法内短路，仅不完整且 tmdbId 非空时实际需要网络）；
     * 否则 {@code fetchDetailV2} 拉详情逐字段合并——本地非空字段优先、缺失字段用远端值补齐，
     * rawJson 恒取远端（图片写回需要）；远端拉取失败原样返回（不阻断削刮）。
     */
    public MediaMetadata enrichLocalWithTmdb(MediaMetadata local, String userId, String mediaType) {
        if (local == null || local.getTmdbId() == null || completeSupport.isComplete(local)) {
            return local;
        }
        MediaMetadata remote;
        try {
            remote = tmdbService.fetchDetailV2(userId, local.getTmdbId(), mediaType);
        } catch (Exception e) {
            log.debug("本地元数据 TMDB 补全失败，维持本地: tmdbId={}, error={}", local.getTmdbId(), e.getMessage());
            return local;
        }
        if (remote == null) {
            return local;
        }
        return mergeLocalWithTmdb(local, remote);
    }

    /**
     * 删除 owner 一对一绑定的元数据行（无对应行时无事发生），供未匹配清理与级联删除复用。
     */
    public void deleteByOwner(String ownerType, String ownerId) {
        mediaMetadataMapper.delete(new LambdaQueryWrapper<MediaMetadata>()
                .eq(MediaMetadata::getOwnerType, ownerType)
                .eq(MediaMetadata::getOwnerId, ownerId));
    }

    /**
     * 按 owner 反向指针查询元数据行。
     */
    public MediaMetadata selectByOwner(String ownerType, String ownerId) {
        return mediaMetadataMapper.selectOne(new LambdaQueryWrapper<MediaMetadata>()
                .eq(MediaMetadata::getOwnerType, ownerType)
                .eq(MediaMetadata::getOwnerId, ownerId));
    }

    /**
     * 把新数据字段复制到既有行（保留 id/user_id/owner 指针）。
     */
    private void copyMetadata(MediaMetadata from, MediaMetadata to) {
        to.setTmdbId(from.getTmdbId());
        to.setSource(from.getSource());
        to.setTitle(from.getTitle());
        to.setOriginalTitle(from.getOriginalTitle());
        to.setOverview(from.getOverview());
        to.setReleaseDate(from.getReleaseDate());
        to.setVoteAverage(from.getVoteAverage());
        to.setGenres(from.getGenres());
        to.setPosterFileNodeId(from.getPosterFileNodeId());
        to.setBackdropFileNodeId(from.getBackdropFileNodeId());
        to.setRawJson(from.getRawJson());
    }
}
