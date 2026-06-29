package com.fleyx.jcloud.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.UserStatus;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.permission.PermissionResolver;
import com.fleyx.jcloud.mapper.RoleMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.convert.RoleConvert;
import com.fleyx.jcloud.model.convert.UserConvert;
import com.fleyx.jcloud.model.dto.UserLoginDto;
import com.fleyx.jcloud.model.dto.UserRegisterDto;
import com.fleyx.jcloud.model.po.Role;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.po.UserRole;
import com.fleyx.jcloud.model.vo.LoginVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.AuthService;
import com.fleyx.jcloud.service.SystemInitService;
import com.fleyx.jcloud.util.JwtUtil;
import com.fleyx.jcloud.util.UsernameUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * 认证授权业务实现。
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserConvert userConvert;
    private final RoleConvert roleConvert;
    private final JwtUtil jwtUtil;
    private final PermissionResolver permissionResolver;
    private final SystemInitService systemInitService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVo register(UserRegisterDto dto) {
        String username = UsernameUtil.requireValid(dto.getUsername());
        checkUsernameUnique(username);
        User user = new User();
        user.setUsername(username);
        user.setPassword(encryptPassword(dto.getPassword()));
        user.setEmail(dto.getEmail());
        user.setNickname(dto.getNickname());
        user.setStatus(UserStatus.ENABLED.getCode());
        user.setIsAdmin(0);
        userMapper.insert(user);
        bindCommonUserRole(user.getId());
        return userConvert.poToVo(user);
    }

    private void bindCommonUserRole(String userId) {
        Role role = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>()
                        .eq(Role::getCode, "common_user")
                        .eq(Role::getStatus, UserStatus.ENABLED.getCode())
                        .eq(Role::getDeleteAt, 0L)
        );
        if (role == null) {
            return;
        }
        UserRole userRole = new UserRole();
        userRole.setUserId(userId);
        userRole.setRoleId(role.getId());
        userRoleMapper.insert(userRole);
    }

    @Override
    public LoginVo login(UserLoginDto dto) {
        User user = findActiveUserByUsername(dto.getUsername());
        if (!matchPassword(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        return buildLoginVo(user);
    }

    @Override
    public LoginVo getCurrentUser(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return buildLoginVo(user);
    }

    private LoginVo buildLoginVo(User user) {
        List<String> permissions = permissionResolver.resolvePermissionCodes(user.getId());
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());

        LoginVo vo = new LoginVo();
        vo.setToken(token);
        vo.setUserInfo(toUserVo(user));
        vo.setPermissions(permissions);
        vo.setInitialized(systemInitService.isInitialized());
        return vo;
    }

    private UserVo toUserVo(User user) {
        UserVo vo = userConvert.poToVo(user);
        vo.setIsAdmin(user.isSuperAdmin());
        List<String> roleIds = userRoleMapper.selectRoleIdsByUserId(user.getId());
        if (!roleIds.isEmpty()) {
            List<Role> roles = roleMapper.selectBatchIds(roleIds);
            vo.setRoles(roleConvert.poListToVoList(roles));
        } else {
            vo.setRoles(Collections.emptyList());
        }
        return vo;
    }

    private User findActiveUserByUsername(String username) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, UsernameUtil.normalize(username));
        User user = userMapper.selectOne(wrapper);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (UserStatus.DISABLED.getCode() == user.getStatus()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用");
        }
        return user;
    }

    private void checkUsernameUnique(String username) {
        if (userMapper.countByUsernameIncludingDeleted(username) > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户名已存在");
        }
    }

    private String encryptPassword(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
    }

    private boolean matchPassword(String rawPassword, String encodedPassword) {
        return BCrypt.checkpw(rawPassword, encodedPassword);
    }
}
