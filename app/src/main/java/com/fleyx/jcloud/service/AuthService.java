package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.dto.UserLoginDto;
import com.fleyx.jcloud.model.dto.UserRegisterDto;
import com.fleyx.jcloud.model.vo.LoginVo;
import com.fleyx.jcloud.model.vo.UserVo;

/**
 * 认证授权业务接口。
 */
public interface AuthService {

    /**
     * 用户注册。
     *
     * @param dto 注册信息
     * @return 用户视图
     */
    UserVo register(UserRegisterDto dto);

    /**
     * 用户登录。
     *
     * @param dto 登录信息
     * @return 登录结果（含 Token、用户信息、权限编码）
     */
    LoginVo login(UserLoginDto dto);

    /**
     * 获取当前登录用户完整信息。
     *
     * @param userId 用户 ID
     * @return 用户及权限信息
     */
    LoginVo getCurrentUser(String userId);
}
