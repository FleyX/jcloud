package com.fleyx.jcloud.common.enums;

import lombok.Getter;

/**
 * 媒体类型枚举（视频目录分类）。
 */
@Getter
public enum MediaType {

    /**
     * 电影。
     */
    MOVIE("movie"),

    /**
     * 电视。
     */
    TV("tv"),

    /**
     * 其他（不获取元数据）。
     */
    OTHER("other");

    private final String code;

    MediaType(String code) {
        this.code = code;
    }

    public static MediaType of(String code) {
        for (MediaType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
