package com.fleyx.jcloud.common.constant;

/**
 * 认证相关常量。
 */
public final class AuthConstant {

    private AuthConstant() {
    }

    /**
     * 访问令牌（JWT）cookie 名。
     */
    public static final String ACCESS_TOKEN_COOKIE = "jcloud_access_token";

    /**
     * 刷新令牌 cookie 名。
     */
    public static final String REFRESH_TOKEN_COOKIE = "jcloud_refresh_token";
}
