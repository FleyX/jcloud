package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.model.dto.TokenRefreshDto;
import com.fleyx.jcloud.model.dto.UserLoginDto;
import com.fleyx.jcloud.model.dto.UserRegisterDto;
import com.fleyx.jcloud.model.vo.LoginVo;
import com.fleyx.jcloud.model.vo.TokenPairVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证授权控制器。
 */
@RestController
@RequestMapping(CommonConstant.API + "/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

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
                            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return R.ok(authService.login(dto, userAgent));
    }

    /**
     * 刷新令牌。
     */
    @PostMapping("/refresh")
    public R<TokenPairVo> refresh(@Valid @RequestBody TokenRefreshDto dto) {
        return R.ok(authService.refresh(dto));
    }

    /**
     * 登出当前设备会话（吊销刷新令牌对应的会话）。
     * 登记为 public：访问令牌已过期时仍须能登出（此时客户端只持有刷新令牌）。
     */
    @PostMapping("/logout")
    public R<Void> logout(@Valid @RequestBody TokenRefreshDto dto) {
        authService.logout(dto.getRefreshToken());
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
}
