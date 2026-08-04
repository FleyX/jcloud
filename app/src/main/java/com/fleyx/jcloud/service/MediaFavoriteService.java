package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.enums.MediaFavoriteOwnerType;
import com.fleyx.jcloud.model.dto.MediaFavoriteQueryDto;
import com.fleyx.jcloud.model.vo.MediaFavoriteVo;

import java.util.Collection;
import java.util.Set;

/**
 * 媒体收藏服务：收藏/取消收藏切换与收藏状态查询。
 */
public interface MediaFavoriteService {

    /**
     * 切换收藏状态：已收藏则取消返回 false，未收藏则收藏返回 true（重复 toggle 幂等）。
     * <p>
     * toggle 前校验目标实体存在且属于当前用户（未匹配条目允许收藏，不校验匹配状态）。
     *
     * @param userId    用户 ID
     * @param ownerType 归属实体类型
     * @param ownerId   归属实体 ID
     * @return 切换后的收藏状态
     */
    boolean toggle(String userId, MediaFavoriteOwnerType ownerType, String ownerId);

    /**
     * 批量查询当前用户已收藏的实体 ID 集合（VO favorited 填充用）。
     *
     * @param userId    用户 ID
     * @param ownerType 归属实体类型
     * @param ownerIds  实体 ID 集合，为空直接返回空集
     * @return 已收藏的实体 ID 集合
     */
    Set<String> listFavoritedOwnerIds(String userId, MediaFavoriteOwnerType ownerType, Collection<String> ownerIds);

    /**
     * 按归属实体删除收藏记录（级联清理用，不限用户：实体删除对所有用户生效）。
     *
     * @param ownerType 归属实体类型
     * @param ownerIds  实体 ID 集合
     */
    void deleteByOwners(MediaFavoriteOwnerType ownerType, Collection<String> ownerIds);

    /**
     * 分页查询当前用户某类实体的收藏（按收藏时间倒序），供「我的收藏」页分区加载。
     *
     * @param userId 用户 ID
     * @param query  查询入参（ownerType 必填，directoryId 可选库过滤）
     * @return 收藏条目分页视图
     */
    IPage<MediaFavoriteVo> pageFavorites(String userId, MediaFavoriteQueryDto query);
}
