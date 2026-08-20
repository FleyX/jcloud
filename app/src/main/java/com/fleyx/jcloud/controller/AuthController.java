package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.model.dto.TokenRefreshDto;
import com.fleyx.jcloud.model.dto.UserLoginDto;
import com.fleyx.jcloud.model.dto.UserRegisterDto;
import com.fleyx.jcloud.model.vo.DeviceSessionVo;
import com.fleyx.jcloud.model.vo.LoginVo;
import com.fleyx.jcloud.model.vo.TokenPairVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.AuthService;
import com.fleyx.jcloud.service.support.AuthCookieSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 认证授权控制器。
 */
@RestController
@RequestMapping(CommonConstant.API + "/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthCookieSupport authCookieSupport;

    /**
     * 用户注册。
     */
    @PostMapping("/register")
    public R<UserVo> register(@Valid @RequestBody UserRegisterDto dto) {
        return R.ok(authService.register(dto));
    }

    /**
     * 用户登录。
     */
    @PostMapping("/login")
    public R<LoginVo> login(@Valid @RequestBody UserLoginDto dto,
                            @RequestHeader(value = "User-Agent", required = false) String userAgent,
                            HttpServletResponse response) {
        LoginVo vo = authService.login(dto, userAgent);
        authCookieSupport.writeTokenCookies(response, vo.getToken(), vo.getRefreshToken());
        return R.ok(vo);
    }

    /**
     * 刷新令牌。
     */
    @PostMapping("/refresh")
    public R<TokenPairVo> refresh(@Valid @RequestBody TokenRefreshDto dto, HttpServletResponse response) {
        TokenPairVo vo = authService.refresh(dto);
        authCookieSupport.writeTokenCookies(response, vo.getToken(), vo.getRefreshToken());
        return R.ok(vo);
    }

    /**
     * 登出当前设备会话（吊销刷新令牌对应的会话）。
     * 登记为 public：访问令牌已过期时仍须能登出（此时客户端只持有刷新令牌）。
     * body 可为空：Web 端 JS 读不到 HttpOnly cookie，从请求携带的 cookie 中解析刷新令牌。
     */
    @PostMapping("/logout")
    public R<Void> logout(@RequestBody(required = false) TokenRefreshDto dto,
                          HttpServletRequest request,
                          HttpServletResponse response) {
        String refreshToken = authCookieSupport.resolveRefreshToken(request,
                dto == null ? null : dto.getRefreshToken());
        authService.logout(refreshToken);
        authCookieSupport.clearTokenCookies(response);
        return R.ok();
    }

    /**
     * 登出当前用户全部设备会话。
     */
    @PostMapping("/logout-all")
    public R<Void> logoutAll() {
        authService.logoutAll(UserContext.get().id());
        return R.ok();
    }

    /**
     * 获取当前登录用户信息。
     */
    @GetMapping("/me")
    public R<LoginVo> me() {
        CurrentUser currentUser = UserContext.get();
        return R.ok(authService.getCurrentUser(currentUser.id()));
    }

    /**
     * 查询当前用户设备会话列表。
     */
    @GetMapping("/devices")
    public R<List<DeviceSessionVo>> devices(@RequestParam(value = "deviceId", required = false) String deviceId) {
        return R.ok(authService.listDevices(UserContext.get().id(), deviceId));
    }

    /**
     * 踢出指定设备（吊销该设备会话，允许踢出当前设备自身）。
     */
    @DeleteMapping("/devices/{deviceId}")
    public R<Void> revokeDevice(@PathVariable String deviceId) {
        authService.revokeDevice(UserContext.get().id(), deviceId);
        return R.ok();
    }
}
