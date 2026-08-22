package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.dto.TokenRefreshDto;
import com.fleyx.jcloud.model.dto.UserLoginDto;
import com.fleyx.jcloud.model.dto.UserRegisterDto;
import com.fleyx.jcloud.model.vo.DeviceSessionVo;
import com.fleyx.jcloud.model.vo.LoginVo;
import com.fleyx.jcloud.model.vo.TokenPairVo;
import com.fleyx.jcloud.model.vo.UserVo;

import java.util.List;

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
     * @param dto       登录信息
     * @param userAgent User-Agent
     * @return 登录结果（含 Token、用户信息、权限编码）
     */
    LoginVo login(UserLoginDto dto, String userAgent);

    /**
     * 兼容旧调用（测试等），User-Agent 为空。
     */
    default LoginVo login(UserLoginDto dto) {
        return login(dto, null);
    }

    /**
     * 刷新令牌。
     *
     * @param dto 刷新入参
     * @return 新令牌对
     */
    TokenPairVo refresh(TokenRefreshDto dto);

    /**
     * 登出当前设备会话（吊销 refreshToken 对应的会话，幂等）。
     *
     * @param refreshToken 当前设备刷新令牌
     */
    void logout(String refreshToken);

    /**
     * 登出指定用户全部设备会话。
     *
     * @param userId 用户 ID
     */
    void logoutAll(String userId);

    /**
     * 查询指定用户的设备会话列表，按最近活跃时间倒序。
     *
     * @param userId          用户 ID
     * @param currentDeviceId 当前设备标识（可为空，空则全部 current=false）
     * @return 设备会话视图列表
     */
    List<DeviceSessionVo> listDevices(String userId, String currentDeviceId);

    /**
     * 踢出指定设备（吊销该设备会话，幂等）。
     *
     * @param userId   用户 ID
     * @param deviceId 设备标识
     */
    void revokeDevice(String userId, String deviceId);

    /**
     * 获取当前登录用户完整信息。
     *
     * @param userId 用户 ID
     * @return 用户及权限信息
     */
    LoginVo getCurrentUser(String userId);
}
