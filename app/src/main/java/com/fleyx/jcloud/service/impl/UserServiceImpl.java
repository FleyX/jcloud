package com.fleyx.jcloud.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.collection.CollUtil;
import com.fleyx.jcloud.common.cache.UserPermissionCache;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.UserStatus;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.RoleMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.convert.RoleConvert;
import com.fleyx.jcloud.model.convert.UserConvert;
import com.fleyx.jcloud.model.dto.BatchUserStatusDto;
import com.fleyx.jcloud.model.dto.UserPageQueryDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.dto.UserStatusDto;
import com.fleyx.jcloud.model.dto.UserUpdateDto;
import com.fleyx.jcloud.model.dto.UserUpdateRolesDto;
import com.fleyx.jcloud.model.po.Role;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.po.UserRole;
import com.fleyx.jcloud.model.vo.RoleVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户业务实现。
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserConvert userConvert;
    private final RoleConvert roleConvert;
    private final UserPermissionCache userPermissionCache;

    @Override
    public UserVo saveUser(UserSaveDto dto) {
        checkUsernameUnique(dto.getUsername());
        User user = userConvert.dtoToPo(dto);
        user.setPassword(BCrypt.hashpw(user.getPassword(), BCrypt.gensalt()));
        user.setStatus(UserStatus.ENABLED.getCode());
        user.setIsAdmin(0);
        userMapper.insert(user);
        return enrichUserVo(user);
    }

    @Override
    public UserVo getById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return enrichUserVo(user);
    }

    @Override
    public List<UserVo> listByUsername(String username) {
        if (StrUtil.isBlank(username)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户名关键字不能为空");
        }
        List<User> list = userMapper.selectByUsernameLike(username);
        return enrichUserVos(list);
    }

    @Override
    public boolean removeById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        rejectIfSuperAdmin(user, "不能删除超级管理员账号");
        return userMapper.deleteById(id) > 0;
    }

    @Override
    public IPage<UserVo> pageUsers(UserPageQueryDto dto) {
        Page<User> pageParam = new Page<>(dto.getPageNum(), dto.getPageSize());
        LambdaQueryWrapper<User> wrapper = buildQueryWrapper(dto);
        IPage<User> page = userMapper.selectPage(pageParam, wrapper);
        List<UserVo> records = enrichUserVos(page.getRecords());
        Page<UserVo> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(records);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRoles(UserUpdateRolesDto dto) {
        User user = requireUser(dto.getUserId());
        rejectIfSuperAdmin(user, "不能修改超级管理员的角色");
        validateRoleIds(dto.getRoleIds());

        LambdaQueryWrapper<UserRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserRole::getUserId, user.getId());
        userRoleMapper.delete(wrapper);

        List<UserRole> relations = dto.getRoleIds().stream()
                .map(roleId -> buildUserRole(user.getId(), roleId))
                .collect(Collectors.toList());
        if (!relations.isEmpty()) {
            userRoleMapper.batchInsert(relations);
        }
        userPermissionCache.evict(user.getId());
    }

    @Override
    public void updateStatus(UserStatusDto dto) {
        User user = requireUser(dto.getUserId());
        rejectIfSuperAdmin(user, "不能禁用/启用超级管理员账号");
        if (dto.getStatus() != UserStatus.ENABLED.getCode() && dto.getStatus() != UserStatus.DISABLED.getCode()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户状态只能是 1（启用）或 0（禁用）");
        }
        User update = new User();
        update.setId(user.getId());
        update.setStatus(dto.getStatus());
        userMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVo updateUser(UserUpdateDto dto) {
        User user = requireUser(dto.getId());
        boolean isSuperAdmin = user.isSuperAdmin();
        if (isSuperAdmin) {
            rejectSuperAdminFieldChange(dto, user);
        }
        validateStatus(dto.getStatus());
        validateRoleIds(dto.getRoleIds());

        User update = buildUserUpdate(user, dto, isSuperAdmin);
        userMapper.updateById(update);

        if (dto.getRoleIds() != null && !isSuperAdmin) {
            updateUserRoles(user.getId(), dto.getRoleIds());
            userPermissionCache.evict(user.getId());
        }

        User updated = userMapper.selectById(user.getId());
        return enrichUserVo(updated);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Long> batchDelete(List<Long> userIds) {
        if (CollUtil.isEmpty(userIds)) {
            return List.of();
        }
        List<User> users = userMapper.selectBatchIds(userIds);
        List<Long> deletableIds = users.stream()
                .filter(u -> !u.isSuperAdmin())
                .map(User::getId)
                .toList();
        if (deletableIds.isEmpty()) {
            return List.of();
        }
        deletableIds.forEach(userMapper::deleteById);
        return deletableIds;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Long> batchUpdateStatus(BatchUserStatusDto dto) {
        validateStatus(dto.getStatus());
        if (CollUtil.isEmpty(dto.getUserIds())) {
            return List.of();
        }
        List<User> users = userMapper.selectBatchIds(dto.getUserIds());
        List<Long> updatableIds = users.stream()
                .filter(u -> !u.isSuperAdmin())
                .map(User::getId)
                .toList();
        if (updatableIds.isEmpty()) {
            return List.of();
        }
        for (Long userId : updatableIds) {
            User update = new User();
            update.setId(userId);
            update.setStatus(dto.getStatus());
            userMapper.updateById(update);
        }
        return updatableIds;
    }

    private void rejectSuperAdminFieldChange(UserUpdateDto dto, User user) {
        if (CollUtil.isNotEmpty(dto.getRoleIds())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "不能修改超级管理员的角色");
        }
        if (dto.getNickname() != null && !dto.getNickname().equals(user.getNickname())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "不能修改超级管理员的昵称");
        }
        if (dto.getStatus() != null && !dto.getStatus().equals(user.getStatus())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "不能禁用/启用超级管理员账号");
        }
    }

    private User buildUserUpdate(User user, UserUpdateDto dto, boolean isSuperAdmin) {
        User update = new User();
        update.setId(user.getId());
        if (!isSuperAdmin && dto.getNickname() != null) {
            update.setNickname(dto.getNickname());
        }
        if (dto.getEmail() != null) {
            update.setEmail(dto.getEmail());
        }
        if (dto.getStatus() != null && !isSuperAdmin) {
            update.setStatus(dto.getStatus());
        }
        if (StrUtil.isNotBlank(dto.getPassword())) {
            update.setPassword(BCrypt.hashpw(dto.getPassword(), BCrypt.gensalt()));
        }
        return update;
    }

    private void validateStatus(Integer status) {
        if (status == null) {
            return;
        }
        if (status != UserStatus.ENABLED.getCode() && status != UserStatus.DISABLED.getCode()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户状态只能是 1（启用）或 0（禁用）");
        }
    }

    private void updateUserRoles(Long userId, List<Long> roleIds) {
        LambdaQueryWrapper<UserRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserRole::getUserId, userId);
        userRoleMapper.delete(wrapper);
        if (CollUtil.isEmpty(roleIds)) {
            return;
        }
        List<UserRole> relations = roleIds.stream()
                .map(roleId -> buildUserRole(userId, roleId))
                .collect(Collectors.toList());
        userRoleMapper.batchInsert(relations);
    }

    private LambdaQueryWrapper<User> buildQueryWrapper(UserPageQueryDto dto) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(User::getCreateTime);
        if (StrUtil.isNotBlank(dto.getUsername())) {
            wrapper.like(User::getUsername, dto.getUsername());
        }
        if (StrUtil.isNotBlank(dto.getNickname())) {
            wrapper.like(User::getNickname, dto.getNickname());
        }
        if (dto.getStatus() != null) {
            wrapper.eq(User::getStatus, dto.getStatus());
        }
        return wrapper;
    }

    private UserVo enrichUserVo(User user) {
        if (user == null) {
            return null;
        }
        return enrichUserVo(user, userConvert.poToVo(user));
    }

    private UserVo enrichUserVo(User user, UserVo vo) {
        if (vo == null) {
            return null;
        }
        boolean isSuperAdmin = user != null && user.isSuperAdmin();
        vo.setIsAdmin(isSuperAdmin);
        List<Long> roleIds = userRoleMapper.selectRoleIdsByUserId(vo.getId());
        List<RoleVo> roles = new ArrayList<>();
        if (!roleIds.isEmpty()) {
            roles.addAll(roleConvert.poListToVoList(roleMapper.selectBatchIds(roleIds)));
        }
        if (isSuperAdmin && roles.stream().noneMatch(r -> "super_admin".equals(r.getCode()))) {
            roles.add(0, buildSuperAdminRoleVo());
        }
        vo.setRoles(roles);
        return vo;
    }

    private List<UserVo> enrichUserVos(List<User> users) {
        if (CollUtil.isEmpty(users)) {
            return List.of();
        }
        List<UserVo> vos = users.stream().map(userConvert::poToVo).toList();
        List<Long> userIds = users.stream().map(User::getId).toList();
        Map<Long, Boolean> superAdminMap = users.stream()
                .collect(Collectors.toMap(User::getId, User::isSuperAdmin));

        List<UserRole> userRoles = userRoleMapper.selectByUserIds(userIds);
        Map<Long, List<Long>> userRoleIdsMap = userRoles.stream()
                .collect(Collectors.groupingBy(UserRole::getUserId,
                        Collectors.mapping(UserRole::getRoleId, Collectors.toList())));
        Set<Long> allRoleIds = userRoles.stream()
                .map(UserRole::getRoleId)
                .collect(Collectors.toSet());
        Map<Long, Role> roleMap = allRoleIds.isEmpty() ? Map.of()
                : roleMapper.selectBatchIds(new ArrayList<>(allRoleIds)).stream()
                .collect(Collectors.toMap(Role::getId, r -> r));

        for (int i = 0; i < vos.size(); i++) {
            UserVo vo = vos.get(i);
            Long uid = vo.getId();
            boolean isSuperAdmin = superAdminMap.getOrDefault(uid, false);
            vo.setIsAdmin(isSuperAdmin);
            List<Long> roleIds = userRoleIdsMap.getOrDefault(uid, List.of());
            List<RoleVo> roles = roleIds.stream()
                    .map(roleMap::get)
                    .filter(Objects::nonNull)
                    .map(roleConvert::poToVo)
                    .collect(Collectors.toList());
            if (isSuperAdmin && roles.stream().noneMatch(r -> "super_admin".equals(r.getCode()))) {
                roles.add(0, buildSuperAdminRoleVo());
            }
            vo.setRoles(roles);
        }
        return vos;
    }

    private RoleVo buildSuperAdminRoleVo() {
        RoleVo vo = new RoleVo();
        vo.setCode("super_admin");
        vo.setName("超级管理员");
        vo.setDescription("系统内置超级管理员");
        return vo;
    }

    private User requireUser(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private void rejectIfSuperAdmin(User user, String message) {
        if (user.isSuperAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN, message);
        }
    }

    private void validateRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        long validCount = roleMapper.selectBatchIds(roleIds).size();
        if (validCount != roleIds.size()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "存在无效的角色 ID");
        }
    }

    private UserRole buildUserRole(Long userId, Long roleId) {
        UserRole relation = new UserRole();
        relation.setUserId(userId);
        relation.setRoleId(roleId);
        relation.setCreateTime(LocalDateTime.now());
        return relation;
    }

    private void checkUsernameUnique(String username) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        if (userMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户名已存在");
        }
    }
}
