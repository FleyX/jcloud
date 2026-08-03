package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaFavoriteOwnerType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaFavoriteMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaFavorite;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.service.MediaFavoriteService;
import com.fleyx.jcloud.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 媒体收藏服务实现。
 * <p>
 * toggle 幂等：唯一约束 (user_id, owner_type, owner_id) 兜底，并发重复插入视为已收藏返回 true；
 * 已收藏再次 toggle 则删除返回 false。级联清理按 owner_type + owner_id 删除（不限用户）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaFavoriteServiceImpl implements MediaFavoriteService {

    private final MediaFavoriteMapper mediaFavoriteMapper;
    private final MediaMovieMapper mediaMovieMapper;
    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaOtherMapper mediaOtherMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggle(String userId, MediaFavoriteOwnerType ownerType, String ownerId) {
        requireOwnedEntity(userId, ownerType, ownerId);
        MediaFavorite existing = mediaFavoriteMapper.selectOne(new LambdaQueryWrapper<MediaFavorite>()
                .eq(MediaFavorite::getUserId, userId)
                .eq(MediaFavorite::getOwnerType, ownerType.getCode())
                .eq(MediaFavorite::getOwnerId, ownerId));
        if (existing != null) {
            mediaFavoriteMapper.deleteById(existing.getId());
            log.info("取消收藏 userId={} ownerType={} ownerId={}", userId, ownerType.getCode(), ownerId);
            return false;
        }
        MediaFavorite favorite = new MediaFavorite();
        favorite.setId(IdUtil.nextId());
        favorite.setUserId(userId);
        favorite.setOwnerType(ownerType.getCode());
        favorite.setOwnerId(ownerId);
        try {
            mediaFavoriteMapper.insert(favorite);
        } catch (DuplicateKeyException e) {
            // 并发重复点击：唯一约束兜底，视为已收藏（幂等）
            log.info("并发重复收藏（唯一约束兜底）userId={} ownerType={} ownerId={}",
                    userId, ownerType.getCode(), ownerId);
            return true;
        }
        log.info("收藏 userId={} ownerType={} ownerId={}", userId, ownerType.getCode(), ownerId);
        return true;
    }

    @Override
    public Set<String> listFavoritedOwnerIds(String userId, MediaFavoriteOwnerType ownerType,
                                             Collection<String> ownerIds) {
        List<String> distinctIds = distinctNonBlank(ownerIds);
        if (distinctIds.isEmpty()) {
            return Set.of();
        }
        return mediaFavoriteMapper.selectList(new LambdaQueryWrapper<MediaFavorite>()
                        .eq(MediaFavorite::getUserId, userId)
                        .eq(MediaFavorite::getOwnerType, ownerType.getCode())
                        .in(MediaFavorite::getOwnerId, distinctIds)
                        .select(MediaFavorite::getOwnerId))
                .stream()
                .map(MediaFavorite::getOwnerId)
                .collect(Collectors.toSet());
    }

    @Override
    public void deleteByOwners(MediaFavoriteOwnerType ownerType, Collection<String> ownerIds) {
        List<String> distinctIds = distinctNonBlank(ownerIds);
        if (distinctIds.isEmpty()) {
            return;
        }
        int deleted = mediaFavoriteMapper.delete(new LambdaQueryWrapper<MediaFavorite>()
                .eq(MediaFavorite::getOwnerType, ownerType.getCode())
                .in(MediaFavorite::getOwnerId, distinctIds));
        if (deleted > 0) {
            log.info("级联清理收藏 {} 条: ownerType={} ownerIds={}", deleted, ownerType.getCode(), distinctIds);
        }
    }

    /**
     * 校验目标实体存在且属于当前用户；不存在或越权抛业务异常。
     * 未匹配条目允许收藏（不校验 match_status）。
     */
    private void requireOwnedEntity(String userId, MediaFavoriteOwnerType ownerType, String ownerId) {
        boolean owned = switch (ownerType) {
            case MOVIE -> {
                MediaMovie movie = mediaMovieMapper.selectById(ownerId);
                yield movie != null && userId.equals(movie.getUserId());
            }
            case SERIES -> {
                MediaSeries series = mediaSeriesMapper.selectById(ownerId);
                yield series != null && userId.equals(series.getUserId());
            }
            case SEASON -> {
                MediaSeason season = mediaSeasonMapper.selectById(ownerId);
                yield season != null && seriesOwnedBy(userId, season.getSeriesId());
            }
            case EPISODE -> {
                MediaEpisode episode = mediaEpisodeMapper.selectById(ownerId);
                yield episode != null && seriesOwnedBy(userId, episode.getSeriesId());
            }
            case OTHER -> {
                MediaOther other = mediaOtherMapper.selectById(ownerId);
                yield other != null && userId.equals(other.getUserId());
            }
        };
        if (!owned) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
        }
    }

    /**
     * 季/集归属校验：季/集行不直接存用户 ID，通过其所属剧行归属判定。
     */
    private boolean seriesOwnedBy(String userId, String seriesId) {
        if (seriesId == null) {
            return false;
        }
        MediaSeries series = mediaSeriesMapper.selectById(seriesId);
        return series != null && userId.equals(series.getUserId());
    }

    private List<String> distinctNonBlank(Collection<String> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) {
            return List.of();
        }
        return ownerIds.stream().filter(Objects::nonNull).filter(id -> !id.isBlank()).distinct().toList();
    }
}
