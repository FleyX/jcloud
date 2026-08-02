package com.fleyx.jcloud.common.enums;

import lombok.Getter;

/**
 * 媒体元数据归属实体类型枚举（t_media_metadata_v2.owner_type）。
 */
@Getter
public enum MediaMetadataOwnerType {

    /**
     * 电影。
     */
    MOVIE("movie"),

    /**
     * 剧集。
     */
    SERIES("series"),

    /**
     * 季。
     */
    SEASON("season"),

    /**
     * 集。
     */
    EPISODE("episode");

    private final String code;

    MediaMetadataOwnerType(String code) {
        this.code = code;
    }
}
