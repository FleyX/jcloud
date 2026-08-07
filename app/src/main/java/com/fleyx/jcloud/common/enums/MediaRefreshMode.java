package com.fleyx.jcloud.common.enums;

import lombok.Getter;

/**
 * 媒体元数据刷新模式枚举（单条刷新两模式，工单 06/08）。
 */
@Getter
public enum MediaRefreshMode {

    /**
     * 缺失补全：补齐缺失文本字段并校验图片/NFO 产物缺失则重建，不覆盖已有字段。
     */
    MISSING("missing"),

    /**
     * 强制刷新：重新拉取 TMDB 全量覆盖字段并全量替换图片/NFO 产物。
     */
    FORCE("force");

    private final String code;

    MediaRefreshMode(String code) {
        this.code = code;
    }

    /**
     * 按编码解析刷新模式（HTTP 入参字符串值缺失/强制）；未知或为空返回 null，
     * 由调用方按参数错误处理。
     *
     * @param code 刷新模式编码，可为空
     * @return 枚举或 null
     */
    public static MediaRefreshMode of(String code) {
        if (code == null) {
            return null;
        }
        for (MediaRefreshMode mode : values()) {
            if (mode.code.equals(code)) {
                return mode;
            }
        }
        return null;
    }
}
