package com.fleyx.jcloud.common.context;

/**
 * 当前登录用户信息。
 *
 * @param id       用户 ID
 * @param userCode 用户 code
 */
public record CurrentUser(String id, String userCode) {
}
