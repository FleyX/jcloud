package com.fleyx.jcloud.common.enums;

import lombok.Getter;

/**
 * 媒体条目类型枚举。
 */
@Getter
public enum MediaItemType {

    /**
     * 电影。
     */
    MOVIE("movie"),

    /**
     * 剧集。
     */
    SERIES("series"),

    /**
     * 集。
     */
    EPISODE("episode"),

    /**
     * 其他。
     */
    OTHER("other");

    private final String code;

    MediaItemType(String code) {
        this.code = code;
    }
}
