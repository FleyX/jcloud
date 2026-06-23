package com.fleyx.jcloud.common.context;

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
     * 清除当前用户。
     */
    public static void clear() {
        HOLDER.remove();
    }
}
