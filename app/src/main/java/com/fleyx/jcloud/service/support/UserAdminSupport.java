package com.fleyx.jcloud.service.support;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.fleyx.jcloud.common.cache.UserPermissionCache;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.UserStatus;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.convert.UserConvert;
import com.fleyx.jcloud.model.dto.BatchUserStatusDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.dto.UserStorageDto;
import com.fleyx.jcloud.model.dto.UserUpdateDto;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.util.ByteFormatUtil;
import com.fleyx.jcloud.util.UsernameUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 管理端用户管理支撑组件。
 * <p>
 * 承载用户创建、编辑、批量删除/状态变更与存储空间绑定。
 */
@Component
@RequiredArgsConstructor
public class UserAdminSupport {

    private final UserMapper userMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final UserConvert userConvert;
    private final UserPermissionCache userPermissionCache;
    private final UserSpaceSupport userSpaceSupport;
    private final UserRoleSupport userRoleSupport;
    private final UserVoEnrichSupport userVoEnrichSupport;

    /**
     * 创建用户。
     *
     * @param dto 用户信息
     * @return 创建后的用户视图
     */
    @Transactional(rollbackFor = Exception.class)
    public UserVo saveUser(UserSaveDto dto) {
        String username = UsernameUtil.requireValid(dto.getUsername());
        checkUsernameUnique(username);
        StorageSpace space = requireEnabledStorageSpace(dto.getStorageSpaceId());
        long quotaBytes = ByteFormatUtil.parse(dto.getQuota(), dto.getQuotaUnit());

        User user = userConvert.dtoToPo(dto);
        user.setUsername(username);
        user.setPassword(BCrypt.hashpw(user.getPassword(), BCrypt.gensalt()));
        user.setStatus(UserStatus.ENABLED.getCode());
        user.setIsAdmin(0);
        user.setStorageSpaceId(space.getId());
        user.setQuota(quotaBytes);
        user.setUsedSpace(0L);
        user.setReservedSpace(0L);
        userMapper.insert(user);
        return userVoEnrichSupport.enrichUserVo(user);
    }

    /**
     * 更新用户信息（内置管理员仅允许修改常规字段）。
     *
     * @param dto 用户信息
     * @return 更新后的用户视图
     */
    @Transactional(rollbackFor = Exception.class)
    public UserVo updateUser(UserUpdateDto dto) {
        User user = userSpaceSupport.requireUser(dto.getId());
        boolean isBuiltInAdmin = userRoleSupport.isBuiltInAdmin(user);
        if (!isBuiltInAdmin) {
            userRoleSupport.validateStatus(dto.getStatus());
            userRoleSupport.validateRoleIds(dto.getRoleIds());
        }

        User update = buildUserUpdate(user, dto, isBuiltInAdmin);
        userMapper.updateById(update);

        if (dto.getRoleIds() != null && !isBuiltInAdmin) {
            userRoleSupport.replaceUserRoles(user.getId(), dto.getRoleIds());
            userPermissionCache.evict(user.getId());
        }

        User updated = userMapper.selectById(user.getId());
        return userVoEnrichSupport.enrichUserVo(updated);
    }

    /**
     * 批量删除用户（跳过超级管理员）。
     *
     * @param userIds 用户 ID 列表
     * @return 实际删除的用户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public List<String> batchDelete(List<String> userIds) {
        if (CollUtil.isEmpty(userIds)) {
            return List.of();
        }
        List<User> users = userMapper.selectBatchIds(userIds);
        List<String> deletableIds = users.stream()
                .filter(u -> !u.isSuperAdmin())
                .map(User::getId)
                .toList();
        if (deletableIds.isEmpty()) {
            return List.of();
        }
        deletableIds.forEach(userMapper::deleteById);
        return deletableIds;
    }

    /**
     * 批量更新用户状态（跳过超级管理员）。
     *
     * @param dto 用户 ID 列表与目标状态
     * @return 实际更新的用户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public List<String> batchUpdateStatus(BatchUserStatusDto dto) {
        userRoleSupport.validateStatus(dto.getStatus());
        if (CollUtil.isEmpty(dto.getUserIds())) {
            return List.of();
        }
        List<User> users = userMapper.selectBatchIds(dto.getUserIds());
        List<String> updatableIds = users.stream()
                .filter(u -> !u.isSuperAdmin())
                .map(User::getId)
                .toList();
        if (updatableIds.isEmpty()) {
            return List.of();
        }
        for (String userId : updatableIds) {
            User update = new User();
            update.setId(userId);
            update.setStatus(dto.getStatus());
            userMapper.updateById(update);
        }
        return updatableIds;
    }

    /**
     * 绑定存储空间与配额。
     *
     * @param dto 用户 ID、存储空间 ID 与配额
     */
    @Transactional(rollbackFor = Exception.class)
    public void bindStorageSpace(UserStorageDto dto) {
        User user = userSpaceSupport.requireUser(dto.getUserId());
        StorageSpace space = requireEnabledStorageSpace(dto.getStorageSpaceId());
        long quota = dto.getQuota() == null ? 0L : dto.getQuota();
        if (quota > 0 && quota > space.getCapacity()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户配额不能超过存储空间容量");
        }
        user.setStorageSpaceId(space.getId());
        user.setQuota(quota);
        userMapper.updateById(user);
    }

    private User buildUserUpdate(User user, UserUpdateDto dto, boolean isBuiltInAdmin) {
        User update = new User();
        update.setId(user.getId());
        if (dto.getNickname() != null) {
            update.setNickname(dto.getNickname());
        }
        if (dto.getEmail() != null) {
            update.setEmail(dto.getEmail());
        }
        if (dto.getStatus() != null && !isBuiltInAdmin) {
            update.setStatus(dto.getStatus());
        }
        if (StrUtil.isNotBlank(dto.getPassword())) {
            update.setPassword(BCrypt.hashpw(dto.getPassword(), BCrypt.gensalt()));
        }
        if (dto.getQuota() != null) {
            update.setQuota(ByteFormatUtil.parse(dto.getQuota(), dto.getQuotaUnit()));
        }
        return update;
    }

    private void checkUsernameUnique(String username) {
        if (userMapper.countByUsernameIncludingDeleted(username) > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户名已存在");
        }
    }

    private StorageSpace requireEnabledStorageSpace(String storageSpaceId) {
        if (storageSpaceId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "存储空间不能为空");
        }
        StorageSpace space = storageSpaceMapper.selectById(storageSpaceId);
        if (space == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }
        if (!Integer.valueOf(1).equals(space.getStatus())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "存储空间已被禁用");
        }
        return space;
    }
}
