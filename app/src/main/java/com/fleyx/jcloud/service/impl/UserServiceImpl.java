package com.fleyx.jcloud.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.BatchUserStatusDto;
import com.fleyx.jcloud.model.dto.ChangePasswordDto;
import com.fleyx.jcloud.model.dto.UserPageQueryDto;
import com.fleyx.jcloud.model.dto.UserProfileUpdateDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.dto.UserStatusDto;
import com.fleyx.jcloud.model.dto.UserStorageDto;
import com.fleyx.jcloud.model.dto.UserUpdateDto;
import com.fleyx.jcloud.model.dto.UserUpdateRolesDto;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.UserProfileVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.UserService;
import com.fleyx.jcloud.service.support.UserAdminSupport;
import com.fleyx.jcloud.service.support.UserProfileSupport;
import com.fleyx.jcloud.service.support.UserRoleSupport;
import com.fleyx.jcloud.service.support.UserSpaceSupport;
import com.fleyx.jcloud.service.support.UserVoEnrichSupport;
import com.fleyx.jcloud.util.UsernameUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户业务实现。
 * <p>
 * 入口编排类：简单查询在此处理，写操作委托给各支撑组件
 * （{@link UserAdminSupport}、{@link UserRoleSupport}、{@link UserProfileSupport}）。
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final UserSpaceSupport userSpaceSupport;
    private final UserAdminSupport userAdminSupport;
    private final UserRoleSupport userRoleSupport;
    private final UserProfileSupport userProfileSupport;
    private final UserVoEnrichSupport userVoEnrichSupport;

    @Override
    public UserVo saveUser(UserSaveDto dto) {
        return userAdminSupport.saveUser(dto);
    }

    @Override
    public UserVo getById(String id) {
        User user = userSpaceSupport.requireUser(id);
        return userVoEnrichSupport.enrichUserVo(user);
    }

    @Override
    public List<UserVo> listByUsername(String username) {
        if (StrUtil.isBlank(username)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户名关键字不能为空");
        }
        List<User> list = userMapper.selectByUsernameLike(UsernameUtil.normalize(username));
        return userVoEnrichSupport.enrichUserVos(list);
    }

    @Override
    public boolean removeById(String id) {
        User user = userSpaceSupport.requireUser(id);
        userRoleSupport.rejectIfSuperAdmin(user, "不能删除超级管理员账号");
        return userMapper.deleteById(id) > 0;
    }

    @Override
    public IPage<UserVo> pageUsers(UserPageQueryDto dto) {
        Page<User> pageParam = new Page<>(dto.getPageNum(), dto.getPageSize());
        LambdaQueryWrapper<User> wrapper = buildQueryWrapper(dto);
        IPage<User> page = userMapper.selectPage(pageParam, wrapper);
        List<UserVo> records = userVoEnrichSupport.enrichUserVos(page.getRecords());
        Page<UserVo> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(records);
        return result;
    }

    @Override
    public void updateRoles(UserUpdateRolesDto dto) {
        userRoleSupport.updateRoles(dto);
    }

    @Override
    public void updateStatus(UserStatusDto dto) {
        userRoleSupport.updateStatus(dto);
    }

    @Override
    public UserVo updateUser(UserUpdateDto dto) {
        return userAdminSupport.updateUser(dto);
    }

    @Override
    public List<String> batchDelete(List<String> userIds) {
        return userAdminSupport.batchDelete(userIds);
    }

    @Override
    public List<String> batchUpdateStatus(BatchUserStatusDto dto) {
        return userAdminSupport.batchUpdateStatus(dto);
    }

    @Override
    public UserProfileVo getUserProfile(String userId) {
        return userProfileSupport.getUserProfile(userId);
    }

    @Override
    public UserProfileVo updateUserProfile(String userId, UserProfileUpdateDto dto) {
        return userProfileSupport.updateUserProfile(userId, dto);
    }

    @Override
    public void changePassword(String userId, ChangePasswordDto dto) {
        userProfileSupport.changePassword(userId, dto);
    }

    @Override
    public UserProfileVo toggleWebDav(String userId, boolean enabled) {
        return userProfileSupport.toggleWebDav(userId, enabled);
    }

    @Override
    public void bindStorageSpace(UserStorageDto dto) {
        userAdminSupport.bindStorageSpace(dto);
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
}
