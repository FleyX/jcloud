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
     * 获取当前登录用户信息。
     */
    @GetMapping("/me")
    public R<LoginVo> me() {
        CurrentUser currentUser = UserContext.get();
        return R.ok(authService.getCurrentUser(currentUser.id()));
    }
}
