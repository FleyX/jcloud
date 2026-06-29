package com.fleyx.jcloud.common.context;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;

/**
 * 当前登录用户上下文，基于 ThreadLocal 实现。
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    /**
     * 设置当前用户。
     *
     * @param user 当前用户
     */
    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    /**
     * 获取当前用户。
     *
     * @return 当前用户，未登录时返回 null
     */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    /**
     * 获取当前用户，未登录时抛出异常。
     *
     * @return 当前用户
     */
    public static CurrentUser requireCurrentUser() {
        CurrentUser user = get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户未登录");
        }
        return user;
    }

    /**
     * 获取当前用户的登录名（userCode，即 username），未登录时抛出异常。
     *
     * @return 当前用户的 username
     */
    public static String requireUserCode() {
        return requireCurrentUser().userCode();
    }

    /**
     * 清除当前用户。
     */
    public static void clear() {
        HOLDER.remove();
    }
}
