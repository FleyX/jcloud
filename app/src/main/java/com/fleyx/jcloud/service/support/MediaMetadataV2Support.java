package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaMetadataSource;
import com.fleyx.jcloud.common.enums.MediaPersistStatus;
import com.fleyx.jcloud.mapper.MediaMetadataV2Mapper;
import com.fleyx.jcloud.model.po.MediaMetadataV2;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 新模型元数据行（t_media_metadata_v2）生命周期支撑组件（ADR 0021 / issue #20）。
 * <p>
 * 电影/剧集/季/集行各持 metadata_id 一对一关联元数据行，元数据行以 owner_type + owner_id
 * 反向指针同步维护：写入时设置（{@link #upsertByOwner} 按 owner 定位，已存在则原地更新，
 * 否则新建并写入反向指针），删除时由级联支撑组件按 owner 定位清理（{@link #deleteByOwner}）。
 * TMDB 拉取结果（未落库的游离元数据）与本地 NFO 解析结果均经本组件绑定 owner 后落库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaMetadataV2Support {

    private final MediaMetadataV2Mapper mediaMetadataV2Mapper;

    /**
     * 按 owner 反向指针 upsert 元数据行：已存在对应行则原地更新（来源也随新数据变化），
     * 否则新建并写入 owner 指针与默认落盘状态。返回绑定后的元数据行（不修改入参 data）。
     *
     * @param ownerType 归属实体类型（{@link com.fleyx.jcloud.common.enums.MediaMetadataOwnerType}）
     * @param ownerId   归属实体 ID
     * @param data      待绑定元数据（需含 userId 与全部字段）
     */
    public MediaMetadataV2 upsertByOwner(String ownerType, String ownerId, MediaMetadataV2 data) {
        MediaMetadataV2 existing = selectByOwner(ownerType, ownerId);
        if (existing == null) {
            existing = new MediaMetadataV2();
            existing.setUserId(data.getUserId());
            existing.setOwnerType(ownerType);
            existing.setOwnerId(ownerId);
            existing.setPersistStatus(data.getPersistStatus() == null
                    ? MediaPersistStatus.PENDING.getCode() : data.getPersistStatus());
            copyMetadata(data, existing);
            mediaMetadataV2Mapper.insert(existing);
            return existing;
        }
        copyMetadata(data, existing);
        mediaMetadataV2Mapper.updateById(existing);
        return existing;
    }

    /**
     * 本地优先削刮构建 local_nfo 元数据行并绑定 owner：字段以 NFO/本地图片为准，
     * 缺失字段不补（标记不完整由调用方按完整性校验项重算）。
     *
     * @param ownerType      归属实体类型
     * @param ownerId        归属实体 ID
     * @param userId         用户 ID
     * @param data           NFO 解析结果（无 NFO 时为 {@link MediaNfoSupport#emptyData} 空数据）
     * @param posterNodeId   海报图文件节点 ID，可为空
     * @param backdropNodeId 背景图文件节点 ID，可为空
     */
    public MediaMetadataV2 upsertLocal(String ownerType, String ownerId, String userId,
                                       MediaNfoSupport.NfoData data, String posterNodeId, String backdropNodeId) {
        MediaMetadataV2 metadata = new MediaMetadataV2();
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
     * 删除 owner 一对一绑定的元数据行（无对应行时无事发生），供未匹配清理与级联删除复用。
     */
    public void deleteByOwner(String ownerType, String ownerId) {
        mediaMetadataV2Mapper.delete(new LambdaQueryWrapper<MediaMetadataV2>()
                .eq(MediaMetadataV2::getOwnerType, ownerType)
                .eq(MediaMetadataV2::getOwnerId, ownerId));
    }

    /**
     * 按 owner 反向指针查询元数据行。
     */
    public MediaMetadataV2 selectByOwner(String ownerType, String ownerId) {
        return mediaMetadataV2Mapper.selectOne(new LambdaQueryWrapper<MediaMetadataV2>()
                .eq(MediaMetadataV2::getOwnerType, ownerType)
                .eq(MediaMetadataV2::getOwnerId, ownerId));
    }

    /**
     * 把新数据字段复制到既有行（保留 id/user_id/owner 指针）。
     */
    private void copyMetadata(MediaMetadataV2 from, MediaMetadataV2 to) {
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
