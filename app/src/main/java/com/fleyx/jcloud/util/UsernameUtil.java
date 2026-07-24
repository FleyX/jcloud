package com.fleyx.jcloud.util;

import cn.hutool.core.util.StrUtil;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;

import java.util.Locale;
import java.util.Set;

/**
 * 用户名工具类。
 * <p>
 * 用户名作为物理目录名，必须满足文件系统安全规则。
 */
public final class UsernameUtil {

    /**
     * 合法用户名正则：小写字母、数字、下划线，长度 6-32。
     */
    private static final String USERNAME_PATTERN = "^[a-z0-9_]{6,32}$";

    /**
     * 保留名黑名单，避免与系统目录或特殊账号冲突。
     */
    private static final Set<String> RESERVED_NAMES = Set.of(
            "files", "trash", "tmp", "zip-tasks", "system", "admin", "root", "null", "www"
    );

    private UsernameUtil() {
    }

    /**
     * 校验用户名是否合法。
     * <p>
     * 内置管理员 "admin" 作为历史特例放行。
     *
     * @param username 待校验用户名
     * @return 是否合法
     */
    public static boolean isValid(String username) {
        if (StrUtil.isBlank(username)) {
            return false;
        }
        // 内置管理员特例
        if ("admin".equals(username)) {
            return true;
        }
        if (!username.matches(USERNAME_PATTERN)) {
            return false;
        }
        if (RESERVED_NAMES.contains(username)) {
            return false;
        }
        return !username.startsWith("_") && !username.endsWith("_") && !username.contains("__");
    }

    /**
     * 归一化用户名：去除前后空白并转小写。
     *
     * @param username 原始输入
     * @return 归一化后的用户名
     */
    public static String normalize(String username) {
        if (StrUtil.isBlank(username)) {
            return username;
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 校验并归一化用户名，非法时抛出 {@link BusinessException}。
     *
     * @param username 原始输入
     * @return 归一化后的用户名
     */
    public static String requireValid(String username) {
        String normalized = normalize(username);
        if (!isValid(normalized)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户名必须为 6-32 位小写字母、数字或下划线，且不能以下划线开头/结尾或连续出现下划线");
        }
        return normalized;
    }
}
